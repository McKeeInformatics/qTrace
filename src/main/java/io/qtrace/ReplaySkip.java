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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The author's choice to leave an instruction out of the replay — pure JSON, no JavaFX.
 *
 * A step taken out stays in the trace ({@code replay_skip}, with who and when); the Player
 * never runs it. The choice is about the instruction, i.e. its script fragment — the key the
 * replay already deduplicates on — so it applies to every occurrence of that fragment, and
 * when the same fragment occurs several times the last occurrence decides (running it again
 * later is a new instruction, replayed unless taken out too).
 *
 * Only the work since the last stamp can be changed: a stamp validates everything recorded
 * since the previous one (see QTraceExporter#withEarlierWork) and freezes its choices.
 * Every change goes through {@link #set} — the one place to hook an audit trail later.
 */
public final class ReplaySkip {

    static final String FLAG = "replay_skip";
    static final String BY   = "replay_skip_by";
    static final String AT   = "replay_skip_at";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ReplaySkip() {}

    /** Instructions of a step list as the replay sees them: one per fragment. */
    public record Summary(int total, int skipped) {
        public int replayed() { return total - skipped; }
    }

    public static boolean isSkipped(JsonObject step) {
        return flag(step, FLAG);
    }

    /** A step the replay would run — the only kind that can be taken out or put back. */
    public static boolean isReplayable(JsonObject step) {
        return fragment(step) != null && flag(step, "is_scriptable")
            && !flag(step, "deleted") && !flag(step, "replay_excluded");
    }

    /** The step's script fragment as the replay keys it, or null when it has none. */
    public static String fragment(JsonObject step) {
        if (step == null || !step.has("script_fragment") || step.get("script_fragment").isJsonNull()) return null;
        String f = step.get("script_fragment").getAsString().strip();
        return f.isEmpty() ? null : f;
    }

    /**
     * Takes every replayable occurrence of {@code fragment} out of the replay, or puts it back.
     * @return how many steps changed
     */
    public static int set(Iterable<JsonObject> steps, String fragment, boolean skip, String contributor, Instant at) {
        if (fragment == null) return 0;
        String key = fragment.strip();
        int changed = 0;
        for (JsonObject step : steps) {
            if (!isReplayable(step) || !key.equals(fragment(step)) || isSkipped(step) == skip) continue;
            if (skip) {
                step.addProperty(FLAG, true);
                step.addProperty(BY, contributor != null ? contributor : "");
                step.addProperty(AT, (at != null ? at : Instant.now()).toString());
            } else {
                step.remove(FLAG);
                step.remove(BY);
                step.remove(AT);
            }
            changed++;
        }
        return changed;
    }

    /** Hands a step's replay choice (taken out, by whom, when) over to the step that replaces it. */
    static void copy(JsonObject from, JsonObject to) {
        if (!isSkipped(from)) return;
        to.addProperty(FLAG, true);
        if (from.has(BY)) to.add(BY, from.get(BY));
        if (from.has(AT)) to.add(AT, from.get(AT));
    }

    /**
     * {@link #set} over the sessions recorded since the last stamp. That stamp, and everything
     * before it — the unstamped sessions it validated included — is left as signed.
     */
    public static int setInUnstampedSessions(JsonObject root, String fragment, boolean skip,
                                             String contributor, Instant at) {
        List<JsonObject> sessions = sessions(root);
        int changed = 0;
        for (int i = lastStampedIndex(root) + 1; i < sessions.size(); i++)
            changed += set(steps(sessions.get(i)), fragment, skip, contributor, at);
        return changed;
    }

    /** Index of the last stamped session of a .qtrace, or -1 when none was stamped. */
    public static int lastStampedIndex(JsonObject root) {
        List<JsonObject> sessions = sessions(root);
        for (int i = sessions.size() - 1; i >= 0; i--)
            if (StampIntegrity.isStamped(sessions.get(i))) return i;
        return -1;
    }

    /**
     * {@link #setInUnstampedSessions} on a .qtrace file, read just before it is rewritten.
     * Nothing is written when nothing changed; otherwise the file is replaced in one move.
     */
    public static int setInFile(Path qtrace, String fragment, boolean skip, String contributor, Instant at)
            throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(qtrace)).getAsJsonObject();
        int changed = setInUnstampedSessions(root, fragment, skip, contributor, at);
        if (changed == 0) return 0;
        Path tmp = qtrace.resolveSibling(qtrace.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(root));
        try {
            Files.move(tmp, qtrace, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, qtrace, StandardCopyOption.REPLACE_EXISTING);
        }
        return changed;
    }

    /**
     * Carries the choices recorded in a .qtrace over to a fresh capture of the same image
     * (QuPath's workflow history comes back without them). The last occurrence of a fragment
     * in the file decides.
     * @return how many steps were marked
     */
    public static int inherit(Iterable<JsonObject> steps, JsonObject root) {
        Map<String, JsonObject> last = new LinkedHashMap<>();
        for (JsonObject session : sessions(root))
            for (JsonObject step : steps(session))
                if (isReplayable(step)) last.put(fragment(step), step);
        int marked = 0;
        for (JsonObject step : steps) {
            if (!isReplayable(step) || isSkipped(step)) continue;
            JsonObject source = last.get(fragment(step));
            if (source == null || !isSkipped(source)) continue;
            copy(source, step);
            marked++;
        }
        return marked;
    }

    /**
     * What the next stamp answers for: the steps of every session recorded since the last
     * stamp ({@code root}: the image's .qtrace, null when it has none), then those of the
     * capture in progress ({@code live}), in order. Without any stamp, everything recorded.
     */
    public static List<JsonObject> sinceLastStamp(JsonObject root, Iterable<JsonObject> live) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject session : sessions(root)) {
            if (StampIntegrity.isStamped(session)) out.clear();
            else out.addAll(steps(session));
        }
        if (live != null) for (JsonObject step : live) out.add(step);
        return out;
    }

    public static Summary summary(Iterable<JsonObject> steps) {
        Map<String, Boolean> last = new LinkedHashMap<>();
        for (JsonObject step : steps)
            if (isReplayable(step)) last.put(fragment(step), isSkipped(step));
        int skipped = 0;
        for (boolean s : last.values()) if (s) skipped++;
        return new Summary(last.size(), skipped);
    }

    static List<JsonObject> steps(JsonObject session) {
        List<JsonObject> out = new ArrayList<>();
        if (session != null && session.has("steps") && session.get("steps").isJsonArray())
            for (JsonElement el : session.getAsJsonArray("steps"))
                if (el.isJsonObject()) out.add(el.getAsJsonObject());
        return out;
    }

    static List<JsonObject> sessions(JsonObject root) {
        List<JsonObject> out = new ArrayList<>();
        if (root != null && root.has("sessions") && root.get("sessions").isJsonArray())
            for (JsonElement el : root.getAsJsonArray("sessions"))
                if (el.isJsonObject()) out.add(el.getAsJsonObject());
        return out;
    }

    private static boolean flag(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }
}
