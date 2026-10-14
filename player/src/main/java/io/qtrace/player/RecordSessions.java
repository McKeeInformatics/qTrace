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

package io.qtrace.player;

import io.qtrace.StampIntegrity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The sessions of a record (.qtrace, certificate) as the player draws them: one circle per
 * session, like a workflow's packets ({@link WorkflowSource}). A record replays one session or
 * its whole history — the engine merges the sessions of a history, an instruction counting
 * once. Pure, no JavaFX.
 */
final class RecordSessions {

    private RecordSessions() {}

    /**
     * What each session is and the day it was saved: "Certified 2026-07-29" for the session the
     * certificate covers, "Stamped …" for another stamped one, "Unstamped …" for autosaved work.
     *
     * @param certifiedSession index of the session a certificate covers, -1 for a local .qtrace
     */
    static List<String> titles(String json, int certifiedSession) {
        List<String> out = new ArrayList<>();
        JsonArray sessions = sessions(json);
        for (int i = 0; i < sessions.size(); i++) {
            JsonObject session = sessions.get(i).isJsonObject() ? sessions.get(i).getAsJsonObject() : new JsonObject();
            boolean stamped = StampIntegrity.isStamped(session);
            String kind = i == certifiedSession ? "Certified" : stamped ? "Stamped" : "Unstamped";
            String when = stamped && session.has("validation") && session.get("validation").isJsonObject()
                ? str(session.getAsJsonObject("validation"), "timestamp") : "";
            if (when.isBlank()) when = str(session, "exported_at");
            out.add(when.length() >= 10 ? kind + " " + when.substring(0, 10) : kind);
        }
        return out;
    }

    /** How many instructions each session replays when it is played alone. */
    static List<Integer> instructionCounts(String json) {
        List<Integer> out = new ArrayList<>();
        int n = sessions(json).size();
        for (int i = 0; i < n; i++)
            out.add((int) QTraceReplayEngine.withoutSkipped(QTraceReplayEngine.extractSteps(json, i)).stream()
                .filter(ReplayStep::isExecutable).count());
        return out;
    }

    private static JsonArray sessions(String json) {
        if (json == null) return new JsonArray();
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() && el.getAsJsonObject().has("sessions") && el.getAsJsonObject().get("sessions").isJsonArray()
                ? el.getAsJsonObject().getAsJsonArray("sessions") : new JsonArray();
        } catch (RuntimeException e) {
            return new JsonArray();
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
