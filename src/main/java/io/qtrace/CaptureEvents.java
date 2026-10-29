/*
 * qTrace — QuPath workflow provenance extension
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tells modules what qTrace has just captured, the moment it is captured — so a view (the
 * Logbook) can show, live, what goes into the record. Nothing here changes what is recorded:
 * {@link ActionLogger} announces what it already wrote. A module registers one listener when it
 * is installed; only the modules the licence includes are told ({@link ModuleEntitlements}).
 *
 * Called on the capturing thread (often the FX thread, not always): a listener that draws hops
 * to the FX thread itself. The last {@link #MAX_RECENT} captures are kept, so a window opened
 * later starts with what happened before it.
 */
public final class CaptureEvents {

    /** What made the capture. A newer Core may add kinds: a module shows an unknown one plainly. */
    public static final String ANNOTATION = "annotation";
    public static final String COMMAND    = "command";
    public static final String SCRIPT     = "script";
    public static final String CLASSIFIER = "classifier";
    public static final String CORRECTION = "correction";
    public static final String DISPLAY    = "display";
    public static final String ALIGNMENT  = "alignment";
    public static final String IMPORT     = "import";
    public static final String STAMP      = "stamp";
    /** Not a capture: recording moved to another image (text: its name). */
    public static final String IMAGE      = "image";
    /** Not a capture: Reset cleared the capture, which starts over. */
    public static final String RESET      = "reset";
    /** Not a capture: capture paused (nothing recorded until resumed; the record declares it). */
    public static final String PAUSE      = "pause";
    /** Not a capture: capture resumed (text: how many actions the pause left out). */
    public static final String RESUME     = "resume";
    /** Not a capture: a step's compute time, known once it ended (json: its timestamp, command, duration_ms). */
    public static final String DURATION   = "duration";

    public static final int MAX_RECENT = 200;

    /**
     * @param text one readable line, as the Activity log words it
     * @param json what the record keeps of it, as compact JSON (a step with its whole script); null when it is kept elsewhere
     */
    public record Event(String kind, String text, String json, Instant at) {}

    /**
     * A long action of one kind started ({@code running}) or ended: a script run from the editor,
     * a step of a replayed trace. Its capture, if any, comes as an {@link Event} at its end.
     */
    public record Activity(String kind, String text, boolean running) {}

    private static final Map<String, Consumer<Event>> LISTENERS = new LinkedHashMap<>();
    private static final ArrayDeque<Event> RECENT = new ArrayDeque<>();
    private static final Map<String, Consumer<Activity>> ACTIVITY = new LinkedHashMap<>();
    private record Running(String text, Instant since) {}
    private static final Map<String, Running> RUNNING = new LinkedHashMap<>(); // kind → its words, since when

    private CaptureEvents() {}

    /** @param module the registering module's Qtrace-Module name, e.g. "logbook" — one listener each */
    public static synchronized void register(String module, Consumer<Event> onCaptured) {
        if (module != null && onCaptured != null) LISTENERS.put(module, onCaptured);
    }

    /** @param module the registering module's Qtrace-Module name — one listener each */
    public static synchronized void registerActivity(String module, Consumer<Activity> onActivity) {
        if (module != null && onActivity != null) ACTIVITY.put(module, onActivity);
    }

    /** The kinds running right now, for a window opened in the middle of one. */
    public static synchronized java.util.Set<String> running() {
        return java.util.Set.copyOf(RUNNING.keySet());
    }

    /** Since when an action of this kind runs; null when none does. */
    static synchronized Instant runningSince(String kind) {
        Running r = RUNNING.get(kind);
        return r == null ? null : r.since();
    }

    static void started(String kind, String text) {
        if (kind == null) return;
        synchronized (CaptureEvents.class) { RUNNING.put(kind, new Running(text == null ? kind : text, Instant.now())); }
        tell(new Activity(kind, text, true));
    }

    static void finished(String kind) {
        Running r;
        synchronized (CaptureEvents.class) { r = RUNNING.remove(kind); }
        if (r != null) tell(new Activity(kind, r.text(), false));
    }

    private static void tell(Activity activity) {
        List<Map.Entry<String, Consumer<Activity>>> listeners;
        synchronized (CaptureEvents.class) { listeners = new ArrayList<>(ACTIVITY.entrySet()); }
        for (Map.Entry<String, Consumer<Activity>> l : listeners) {
            if (!ModuleEntitlements.isEntitled(l.getKey())) continue;
            try {
                l.getValue().accept(activity);
            } catch (RuntimeException | LinkageError e) {
                // A module's view must never get in the way of the work.
            }
        }
    }

    /** The last captures, oldest first. */
    public static synchronized List<Event> recent() {
        return List.copyOf(RECENT);
    }

    /** What a QuPath workflow step was made by, from the name qTrace or QuPath gave it. */
    static String kindOfStep(String command) {
        if (command == null) return COMMAND;
        if (command.startsWith("Manual annotation")) return ANNOTATION;
        if (command.startsWith("Script: ")) return SCRIPT;
        if (command.startsWith("Import objects from file")) return IMPORT;
        return COMMAND;
    }

    static void captured(String kind, String text, JsonElement json) {
        if (kind == null || text == null) return;
        Event event = new Event(kind, text, json == null ? null : json.toString(), Instant.now());
        List<Map.Entry<String, Consumer<Event>>> listeners;
        synchronized (CaptureEvents.class) {
            RECENT.addLast(event);
            while (RECENT.size() > MAX_RECENT) RECENT.removeFirst();
            listeners = new ArrayList<>(LISTENERS.entrySet());
        }
        // Outside the lock: a listener may take its time, or read recent() itself.
        for (Map.Entry<String, Consumer<Event>> l : listeners) {
            if (!ModuleEntitlements.isEntitled(l.getKey())) continue;
            try {
                l.getValue().accept(event);
            } catch (RuntimeException | LinkageError e) {
                // A module's view must never get in the way of a capture.
            }
        }
    }

    // ── What a view is shown of a capture ───────────────────────────────────

    /** A correction record's own fields, without the objects it lists (which can be thousands). */
    static JsonObject scalars(JsonObject record) {
        JsonObject out = new JsonObject();
        record.entrySet().forEach(e -> { if (e.getValue().isJsonPrimitive()) out.add(e.getKey(), e.getValue()); });
        return out;
    }

    static JsonObject matrix(double[] matrix) {
        JsonObject out = new JsonObject();
        JsonArray values = new JsonArray();
        if (matrix != null) for (double v : matrix) values.add(v);
        out.add("matrix", values);
        return out;
    }

    static JsonObject classifier(String name, String sha256) {
        JsonObject out = new JsonObject();
        out.addProperty("classifier", name);
        if (sha256 != null) out.addProperty("sha256", sha256);
        return out;
    }

    /** Reset: the capture starts over, so does what a window opened later is shown. */
    static synchronized void forgetRecent() {
        RECENT.clear();
    }

    static synchronized void clear() {
        LISTENERS.clear();
        RECENT.clear();
        ACTIVITY.clear();
        RUNNING.clear();
    }
}
