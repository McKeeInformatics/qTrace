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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Record" in the Version window's editing mode ({@link VersionEditor}): which instructions the
 * user has done in QuPath since pressing it — what goes into the workflow's recording packet.
 * Pure JSON, no JavaFX.
 *
 * It reads the capture in progress (the host's live session) and keeps the steps that were not
 * there when recording started, that the replay would run, and that were done now rather than
 * read from an image's QuPath history. The capture is read again each time: a step whose script
 * changes afterwards (an annotation being reshaped) is recorded as it is at the end.
 */
final class WorkflowRecording {

    private final Set<String> before = new HashSet<>();

    /** @param liveAtStart the capture in progress when Record is pressed, or null when there is none */
    WorkflowRecording(JsonObject liveAtStart) {
        for (JsonObject st : steps(liveAtStart)) before.add(key(st));
    }

    /** The instructions done since Record, in the order they were done, as the capture has them now. */
    List<JsonObject> recorded(JsonObject liveNow) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject st : steps(liveNow)) {
            if (before.contains(key(st))) continue;
            if (st.has("pre_tracking") && st.get("pre_tracking").getAsBoolean()) continue;
            if (!ReplaySkip.isReplayable(st) || ReplaySkip.isSkipped(st)) continue;
            out.add(st);
        }
        return out;
    }

    /** A step is the same one as long as it was captured at the same time by the same command. */
    private static String key(JsonObject step) {
        return str(step, "timestamp") + "|" + str(step, "command");
    }

    private static List<JsonObject> steps(JsonObject live) {
        List<JsonObject> out = new ArrayList<>();
        if (live == null || !live.has("steps") || !live.get("steps").isJsonArray()) return out;
        for (JsonElement el : live.getAsJsonArray("steps")) if (el.isJsonObject()) out.add(el.getAsJsonObject());
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
