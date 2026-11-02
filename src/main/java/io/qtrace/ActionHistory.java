/*
 * qTrace - QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */
package io.qtrace;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which captured actions QuPath's Undo / Redo left in force - docs/architecture/undo-redo.md,
 * "Design". An action is one gesture in QuPath (a command, a drawing, an edit); its number is
 * carried by every step, correction and Logbook line it produces, and never changes.
 *
 * QuPath's only undo is its object hierarchy: a line of serialized snapshots (byte[]) per viewer,
 * read by {@link UndoLine}. {@link #follow} is given that line after each change; a snapshot never
 * seen before belongs to the open action (QuPath writes a command's step after its hierarchy
 * events, so its snapshots come first). An action is in force while QuPath stands on its result
 * or later: its result is the earliest of its last snapshots holding the same hierarchy, so a
 * trailing snapshot identical to the one before (Expand annotations) is no step back. An action
 * without snapshot cannot be undone by QuPath, and stays in force.
 *
 * A snapshot that holds a running plugin's ParallelTileObjects (a tiled detection's first one)
 * cannot be restored by QuPath (0.5.1 to 0.7.0: no public no-arg constructor): moving onto it
 * fails, its line moves but the hierarchy stays. So the state QuPath holds is tracked apart
 * ({@code standing}) from where its line stands.
 *
 * Holds the snapshots QuPath still holds, never more. Thread-safe: hierarchy events and captures
 * come from plugin threads, Undo / Redo from the FX thread.
 */
final class ActionHistory {

    static final String ACTIVE = "active", UNDONE = "undone", ABANDONED = "abandoned";

    /** One count per QuPath run: two images' lines never share a number. */
    private static final AtomicLong NEXT = new AtomicLong(1);

    private static final class Action {
        final long number;
        String state = ACTIVE;
        boolean captured;
        boolean command;      // a command's step was captured for it (not a "Manual annotation")
        String undoneAt;
        String abandonedAt;
        final List<Object> snapshots = new ArrayList<>();      // those QuPath still holds, oldest first
        final List<JsonObject> records = new ArrayList<>();    // carry "undo" while not in force
        final List<Runnable[]> retired = new ArrayList<>();    // {when undone, when redone}
        Action(long number) { this.number = number; }
    }

    private final Map<Long, Action> actions = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Long> seenAt = new IdentityHashMap<>(); // snapshot → order first seen
    private final IdentityHashMap<Object, Boolean> unrestorable = new IdentityHashMap<>();
    private long seen = 0;
    private Object standing;   // the snapshot whose hierarchy QuPath holds now
    private Action open;

    private static final byte[] TILES = "qupath.lib.plugins.ParallelTileObject".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    // ── Numbers ──────────────────────────────────────────────────────────────

    /** The number of the action under way (the open one), opened if none is. */
    synchronized long current() {
        if (open == null) open = add(NEXT.getAndIncrement());
        return open.number;
    }

    /** Something was captured for the action under way: its number. Seal it on the next pulse. */
    synchronized long captured() {
        current();
        open.captured = true;
        return open.number;
    }

    /** An action of its own, outside the one under way: a capture that never touches the hierarchy. */
    synchronized long standalone() {
        Action a = add(NEXT.getAndIncrement());
        a.captured = true;
        return a.number;
    }

    /** Closes the action under way once something was captured for it; one still waiting for its capture stays open. */
    synchronized void seal() {
        if (open != null && open.captured) open = null;
    }

    /** Capture paused or resumed: what the open action gathered without a capture belongs to no action. */
    synchronized void dropOpen() {
        if (open != null && !open.captured) actions.remove(open.number);
        open = null;
    }

    /** A QuPath command's step (not one of qTrace's own "Manual annotation") was captured for {@code action}. */
    synchronized void command(long action) {
        Action a = actions.get(action);
        if (a != null) a.command = true;
    }

    /** An annotation added during {@code action} was made by its command, not drawn by hand. */
    synchronized boolean hasCommand(long action) {
        Action a = actions.get(action);
        return a != null && a.command;
    }

    /** ACTIVE for an action this history does not know (before Reset, another image). */
    synchronized String state(long action) {
        Action a = actions.get(action);
        return a == null ? ACTIVE : a.state;
    }

    /** The actions not in force now, oldest first. */
    synchronized Map<Long, String> notInForce() {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Action a : actions.values()) if (!ACTIVE.equals(a.state)) out.put(a.number, a.state);
        return out;
    }

    // ── What an action holds ─────────────────────────────────────────────────

    /** {@code record} (a step, a correction) was captured for {@code action}: it carries its number, and "undo" while it is not in force. */
    synchronized void hold(long action, JsonObject record) {
        if (record == null) return;
        record.addProperty("action", action);
        Action a = actions.get(action);
        if (a == null) return;
        a.captured = true; // a record held is a capture: the action closes on the next seal
        a.records.add(record);
        mark(a, record);
    }

    /** {@code action} retired an earlier step: {@code undone} puts it back, {@code redone} retires it again. */
    synchronized void supersede(long action, Runnable undone, Runnable redone) {
        Action a = actions.get(action);
        if (a == null) return;
        a.retired.add(new Runnable[] {undone, redone});
        if (!ACTIVE.equals(a.state)) undone.run();
    }

    // ── Following QuPath ─────────────────────────────────────────────────────

    /**
     * QuPath's undo line now: its snapshots oldest first (undo stack, current, redo stack) and the
     * index of the current one; an empty line when QuPath keeps none (undo off for this hierarchy).
     *
     * @param claim false while capture is paused, and for the line found at attach: new snapshots belong to no action
     * @return what changed, at most one change of each kind (undone, redone, abandoned)
     */
    synchronized List<CaptureEvents.ActionChange> follow(List<?> line, int position, boolean claim) {
        IdentityHashMap<Object, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < line.size(); i++) index.put(line.get(i), i);
        long branch = -1; // the newest snapshot already known that QuPath still holds
        Object branchAt = null;
        for (Object s : line) { Long o = seenAt.get(s); if (o != null && o > branch) { branch = o; branchAt = s; } }
        // A new change made right after QuPath failed to restore a snapshot: taken from the hierarchy it kept.
        boolean afterFailedRestore = branchAt != null && unrestorable.containsKey(branchAt);
        for (int i = 0; i < line.size(); i++) {
            Object s = line.get(i);
            if (seenAt.containsKey(s)) continue;
            seenAt.put(s, seen++);
            if (holdsTiles(s)) unrestorable.put(s, true);
            if (claim && i <= position) actions.get(current()).snapshots.add(s);
        }
        // Where QuPath's hierarchy is: where its line stands, unless it could not restore that snapshot.
        Object before = standing;
        int held = position;
        if (position >= 0 && unrestorable.containsKey(line.get(position)) && before != null && index.containsKey(before))
            held = index.get(before);
        standing = held >= 0 ? line.get(held) : null;

        String now = Instant.now().toString();
        List<Long> undone = new ArrayList<>(), redone = new ArrayList<>(), abandoned = new ArrayList<>();
        for (Action a : actions.values()) {
            if (ABANDONED.equals(a.state) || a.snapshots.isEmpty()) continue;
            List<Object> own = a.snapshots;
            if (afterFailedRestore && !index.containsKey(own.get(own.size() - 1)) && own.contains(before)) {
                // Dropped while QuPath's hierarchy was this result (a failed Undo, then a new change):
                // the new snapshot was taken from it. What is left of it stands for it now.
                own.removeIf(s -> !index.containsKey(s));
                if (own.isEmpty()) continue;
            }
            // Its result: the earliest of its last snapshots that hold the same hierarchy.
            Object last = own.get(own.size() - 1);
            int r = own.size() - 1;
            while (r > 0 && sameState(own.get(r - 1), last)) r--;
            Integer at = null; // where QuPath still holds that result, earliest first
            for (int k = r; k < own.size() && at == null; k++) at = index.get(own.get(k));
            if (at == null) {
                // QuPath dropped its result: a Redo branch ended, or the bottom of a full stack.
                boolean wasUndone = UNDONE.equals(a.state);
                boolean droppedFromRedo = branch >= 0 && seenAt.get(own.get(r)) > branch;
                if (!wasUndone && droppedFromRedo) { undo(a, now); undone.add(a.number); }
                if (wasUndone || droppedFromRedo) { abandon(a, now); abandoned.add(a.number); }
                own.clear(); // in force for good otherwise: QuPath can no longer undo it
                continue;
            }
            boolean inForce = at <= held;
            if (ACTIVE.equals(a.state) && !inForce) { undo(a, now); undone.add(a.number); }
            else if (UNDONE.equals(a.state) && inForce) { redo(a); redone.add(a.number); }
        }

        // Only what QuPath still holds.
        seenAt.keySet().removeIf(s -> !index.containsKey(s));
        unrestorable.keySet().removeIf(s -> !index.containsKey(s));
        for (Action a : actions.values()) a.snapshots.removeIf(s -> !index.containsKey(s));

        List<CaptureEvents.ActionChange> out = new ArrayList<>();
        Instant at = Instant.parse(now);
        if (!undone.isEmpty())    out.add(new CaptureEvents.ActionChange(CaptureEvents.ActionChange.UNDONE, undone, at));
        if (!redone.isEmpty())    out.add(new CaptureEvents.ActionChange(CaptureEvents.ActionChange.REDONE, redone, at));
        if (!abandoned.isEmpty()) out.add(new CaptureEvents.ActionChange(CaptureEvents.ActionChange.ABANDONED, abandoned, at));
        return out;
    }

    /** A snapshot with a running plugin's tiles: QuPath fails to restore it. */
    private static boolean holdsTiles(Object snapshot) {
        if (!(snapshot instanceof byte[] b)) return false;
        outer:
        for (int i = 0; i + TILES.length <= b.length; i++) {
            for (int j = 0; j < TILES.length; j++) if (b[i + j] != TILES[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Two snapshots hold the same hierarchy: same bytes (QuPath serializes the same objects alike). */
    private static boolean sameState(Object a, Object b) {
        if (a == b) return true;
        return a instanceof byte[] x && b instanceof byte[] y && Arrays.equals(x, y);
    }

    private void undo(Action a, String at) {
        a.state = UNDONE;
        a.undoneAt = at;
        for (JsonObject r : a.records) mark(a, r);
        for (int i = a.retired.size() - 1; i >= 0; i--) a.retired.get(i)[0].run();
    }

    private void redo(Action a) {
        a.state = ACTIVE;
        a.undoneAt = null;
        for (JsonObject r : a.records) mark(a, r);
        for (Runnable[] r : a.retired) r[1].run();
    }

    private void abandon(Action a, String at) {
        a.state = ABANDONED;
        a.abandonedAt = at;
        for (JsonObject r : a.records) mark(a, r);
    }

    private static void mark(Action a, JsonObject record) {
        if (ACTIVE.equals(a.state)) { record.remove("undo"); return; }
        JsonObject undo = new JsonObject();
        undo.addProperty("state", a.state);
        if (a.undoneAt != null) undo.addProperty("at", a.undoneAt);
        if (a.abandonedAt != null) undo.addProperty("abandoned_at", a.abandonedAt);
        record.add("undo", undo);
    }

    // ── Reset, draft ─────────────────────────────────────────────────────────

    /** Reset: the actions are forgotten, so an Undo afterwards changes nothing recorded. */
    synchronized void forget() {
        actions.clear();
        open = null;
    }

    /**
     * After a crash, from the steps of the live draft: QuPath's stack is gone, so what was undone
     * can never be redone - abandoned. Numbers go on after the highest restored.
     */
    synchronized void restore(List<JsonObject> records) {
        forget();
        String now = Instant.now().toString();
        long max = 0;
        for (JsonObject r : records) {
            if (r == null || !r.has("action")) continue;
            long n = r.get("action").getAsLong();
            max = Math.max(max, n);
            Action a = actions.computeIfAbsent(n, Action::new);
            a.captured = true;
            if (r.has("undo") && r.get("undo").isJsonObject()) {
                JsonObject u = r.getAsJsonObject("undo");
                a.state = ABANDONED;
                if (a.undoneAt == null && u.has("at")) a.undoneAt = u.get("at").getAsString();
                if (a.abandonedAt == null) a.abandonedAt = u.has("abandoned_at") ? u.get("abandoned_at").getAsString() : now;
            }
            a.records.add(r);
        }
        for (Action a : actions.values()) for (JsonObject r : a.records) mark(a, r);
        long next = max + 1;
        NEXT.accumulateAndGet(next, Math::max);
    }

    private Action add(long number) {
        Action a = new Action(number);
        actions.put(number, a);
        return a;
    }
}
