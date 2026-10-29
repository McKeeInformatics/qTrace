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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The capture's pauses in one session, declared in the record (the session's
 * {@code capture_pauses}): when capture was paused and resumed, and what was done meanwhile that
 * the trace does not hold — QuPath commands (counted, the first ones named) and other changes
 * (annotations, display settings…, counted by kind). A pause is a declared gap, never a silent
 * one: whoever reads or stamps the record is told. Not thread-safe: the capture's thread.
 */
final class CapturePauses {

    /** The first commands of a pause are named; the count is always whole. */
    static final int COMMANDS_LISTED = 50;

    private static final class Pause {
        Instant pausedAt, resumedAt;
        int steps;
        final List<String> commands = new ArrayList<>();
        final Map<String, Integer> changes = new LinkedHashMap<>();
    }

    private final List<Pause> pauses = new ArrayList<>();

    boolean isPaused() {
        return !pauses.isEmpty() && pauses.get(pauses.size() - 1).resumedAt == null;
    }

    boolean isEmpty() {
        return pauses.isEmpty();
    }

    void pause(Instant at) {
        if (isPaused()) return;
        Pause p = new Pause();
        p.pausedAt = at;
        pauses.add(p);
    }

    void resume(Instant at) {
        if (isPaused()) pauses.get(pauses.size() - 1).resumedAt = at;
    }

    /** A QuPath command done while paused: not in the trace. */
    void missedStep(String command) {
        if (!isPaused()) return;
        Pause p = pauses.get(pauses.size() - 1);
        p.steps++;
        if (p.commands.size() < COMMANDS_LISTED) p.commands.add(command == null ? "(unnamed)" : command);
    }

    /** Another change done while paused (annotation, display settings…): not in the trace. */
    void missedChange(String kind, int count) {
        if (!isPaused() || count <= 0) return;
        pauses.get(pauses.size() - 1).changes.merge(kind, count, Integer::sum);
    }

    /** Everything the trace does not hold, over every pause. */
    int missedTotal() {
        int n = 0;
        for (Pause p : pauses) {
            n += p.steps;
            for (int c : p.changes.values()) n += c;
        }
        return n;
    }

    /** The pauses missed in the current one alone — what a resume reports. */
    int missedInLast() {
        if (pauses.isEmpty()) return 0;
        Pause p = pauses.get(pauses.size() - 1);
        int n = p.steps;
        for (int c : p.changes.values()) n += c;
        return n;
    }

    int count() {
        return pauses.size();
    }

    JsonArray toJson() {
        JsonArray out = new JsonArray();
        for (Pause p : pauses) {
            JsonObject o = new JsonObject();
            o.addProperty("paused_at", p.pausedAt.toString());
            if (p.resumedAt != null) o.addProperty("resumed_at", p.resumedAt.toString());
            else o.add("resumed_at", JsonNull.INSTANCE); // still paused when the record was written
            o.addProperty("uncaptured_steps", p.steps);
            JsonArray commands = new JsonArray();
            p.commands.forEach(commands::add);
            o.add("uncaptured_commands", commands);
            JsonObject changes = new JsonObject();
            p.changes.forEach(changes::addProperty);
            o.add("uncaptured_changes", changes);
            out.add(o);
        }
        return out;
    }

    /** Back from a draft (crash recovery); null or unreadable: no pause. */
    static CapturePauses fromJson(JsonArray json) {
        CapturePauses out = new CapturePauses();
        if (json == null) return out;
        for (JsonElement e : json) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            try {
                Pause p = new Pause();
                p.pausedAt = Instant.parse(o.get("paused_at").getAsString());
                p.resumedAt = o.has("resumed_at") && !o.get("resumed_at").isJsonNull()
                    ? Instant.parse(o.get("resumed_at").getAsString()) : null;
                p.steps = o.has("uncaptured_steps") ? o.get("uncaptured_steps").getAsInt() : 0;
                if (o.get("uncaptured_commands") instanceof JsonArray a) a.forEach(c -> p.commands.add(c.getAsString()));
                if (o.get("uncaptured_changes") instanceof JsonObject c)
                    c.entrySet().forEach(x -> p.changes.put(x.getKey(), x.getValue().getAsInt()));
                out.pauses.add(p);
            } catch (RuntimeException ignored) {
                // An unreadable pause in a draft: skipped, the others kept.
            }
        }
        return out;
    }
}
