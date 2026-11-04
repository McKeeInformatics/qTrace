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

    /** What colours a session's circle: the stamp's confidence, unless its classifiers say worse. */
    enum Tone { HIGH, MEDIUM, LOW, OTHER, DEGRADED, COMPROMISED, NONE }

    /**
     * One session as the record page's Version graph draws it (web CommitGraph, and the Version
     * window): its author above, its number in a circle, the stamp notes below (or
     * "Session #n", never the scope), then who validated it and the day.
     */
    record Node(int index, String contributor, String initials, String title, String validator,
                boolean signed, boolean stamped, boolean certified, Tone tone, String date) {}

    /** @param certifiedSession index of the session a certificate covers, -1 for a local .qtrace */
    static List<Node> nodes(String json, int certifiedSession) {
        List<Node> out = new ArrayList<>();
        JsonArray sessions = sessions(json);
        for (int i = 0; i < sessions.size(); i++) {
            JsonObject s = sessions.get(i).isJsonObject() ? sessions.get(i).getAsJsonObject() : new JsonObject();
            JsonObject v = s.has("validation") && s.get("validation").isJsonObject() ? s.getAsJsonObject("validation") : new JsonObject();
            boolean stamped = StampIntegrity.isStamped(s);
            String contributor = str(s, "user");
            if (contributor.isBlank()) contributor = "unknown";
            String notes = str(v, "notes").strip();
            String validator = stamped ? str(v, "validator") : "";
            String date = str(s, "exported_at");
            out.add(new Node(i, contributor, initials(contributor),
                notes.isEmpty() ? "Session #" + (i + 1) : notes,
                validator.isBlank() ? null : validator,
                stamped && !str(v, "validatorKeyPub").isBlank(),
                stamped, i == certifiedSession,
                stamped ? tone(str(v, "confidence"), str(v, "classifier_fidelity")) : Tone.NONE,
                date.length() >= 10 ? date.substring(0, 10) : date));
        }
        return out;
    }

    private static Tone tone(String confidence, String fidelity) {
        if ("COMPROMISED".equalsIgnoreCase(fidelity)) return Tone.COMPROMISED;
        if ("DEGRADED".equalsIgnoreCase(fidelity))    return Tone.DEGRADED;
        return switch (confidence.toLowerCase(java.util.Locale.ROOT)) {
            case "high"   -> Tone.HIGH;
            case "medium" -> Tone.MEDIUM;
            case "low"    -> Tone.LOW;
            case ""       -> Tone.NONE;
            default       -> Tone.OTHER;
        };
    }

    /** "CM" for Connor McCracken: first and last initials, as on the record page. */
    static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.strip().split("\\s+");
        String s = parts[0].substring(0, 1);
        if (parts.length > 1) s += parts[parts.length - 1].charAt(0);
        return s.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The hue of an author's badge: the same name always gets the same colour, the one the
     * record page gives it (commit-style.ts contributorColor: |hash| mod 360, Java's own
     * String hash).
     */
    static int hue(String name) {
        return name == null ? 0 : (int) (Math.abs((long) name.hashCode()) % 360);
    }

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
