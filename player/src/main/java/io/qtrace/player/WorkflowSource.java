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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A workflow opened in the player: a {@code .qtflow} written by the workflow editor module
 * (docs/architecture/workflow-editor.md). It has the shape of a .qtrace — one
 * {@code sessions[]} entry per packet — so the replay engine reads it as it is, and playing one
 * packet is replaying one session. What differs is said here: it is composed, not recorded, no
 * packet carries a stamp, and its classifiers sit in a folder beside the file. Pure, no JavaFX.
 */
final class WorkflowSource {

    /** What starts the ID of a workflow published on qtrace.ca, as {@code qtc_} starts a certificate's. */
    static final String ID_PREFIX = "qtw_";

    private WorkflowSource() {}

    static boolean isWorkflow(String json) {
        JsonObject root = parse(json);
        return root != null && root.has("kind") && root.get("kind").isJsonPrimitive()
            && "workflow".equals(root.get("kind").getAsString());
    }

    static String title(String json) {
        JsonObject root = parse(json);
        return root != null ? str(root, "title") : "";
    }

    /** "#1 — title" for each packet, in the order they play. */
    static List<String> packetLabels(String json) {
        List<String> out = new ArrayList<>();
        JsonArray packets = packets(parse(json));
        for (int i = 0; i < packets.size(); i++) {
            String title = packets.get(i).isJsonObject() ? str(packets.get(i).getAsJsonObject(), "title") : "";
            out.add("#" + (i + 1) + (title.isBlank() ? "" : " — " + title));
        }
        return out;
    }

    /** One instruction of the playlist, and the packet (its place in the workflow) it belongs to. */
    record PacketStep(int packet, ReplayStep step) {}

    /** The title of each packet, in the order they play. */
    static List<String> packetTitles(String json) {
        List<String> out = new ArrayList<>();
        for (JsonElement p : packets(parse(json))) out.add(p.isJsonObject() ? str(p.getAsJsonObject(), "title") : "");
        return out;
    }

    /**
     * The instructions of the packets chosen, packet after packet in the order the workflow
     * plays them — whatever order they were picked in — numbered through the whole list. Each
     * packet runs all its instructions: the same script in two packets is two instructions.
     * An instruction is shown under the title its author gave it, not the first line of its script.
     */
    static List<PacketStep> playlist(String json, List<Integer> packets) {
        List<PacketStep> out = new ArrayList<>();
        JsonArray all = packets(parse(json));
        for (int packet : new java.util.TreeSet<>(packets)) {
            if (packet < 0 || packet >= all.size() || !all.get(packet).isJsonObject()) continue;
            List<String> titles = instructionTitles(all.get(packet).getAsJsonObject());
            List<ReplayStep> steps = QTraceReplayEngine.extractSteps(json, packet);
            // The engine puts class changes first and display settings last, each with a title of
            // its own; the instructions in between come in the order of the packet's steps.
            long scripted = steps.stream().filter(WorkflowSource::isInstruction).count();
            int next = 0;
            for (ReplayStep st : steps) {
                String title = isInstruction(st) && scripted == titles.size() && !titles.get(next).isBlank() ? titles.get(next) : st.title();
                if (isInstruction(st)) next++;
                out.add(new PacketStep(packet, new ReplayStep(out.size() + 1, st.type(), title, st.groovyFragment(),
                    st.validated(), st.sessionId(), st.skipped())));
            }
        }
        return out;
    }

    private static boolean isInstruction(ReplayStep st) {
        return !"Class list".equals(st.type()) && !"Display settings".equals(st.type());
    }

    /** The titles of a packet's instructions as the engine orders them: one per script, at its last occurrence. */
    private static List<String> instructionTitles(JsonObject packet) {
        java.util.LinkedHashMap<String, String> byScript = new java.util.LinkedHashMap<>();
        if (packet.has("steps") && packet.get("steps").isJsonArray()) {
            for (JsonElement el : packet.getAsJsonArray("steps")) {
                if (!el.isJsonObject()) continue;
                JsonObject st = el.getAsJsonObject();
                String script = str(st, "script_fragment").strip();
                boolean scriptable = st.has("is_scriptable") && st.get("is_scriptable").isJsonPrimitive() && st.get("is_scriptable").getAsBoolean();
                boolean deleted = st.has("deleted") && st.get("deleted").isJsonPrimitive() && st.get("deleted").getAsBoolean();
                if (script.isEmpty() || !scriptable || deleted) continue;
                byScript.remove(script);
                byScript.put(script, str(st, "command"));
            }
        }
        return new ArrayList<>(byScript.values());
    }

    /**
     * Completes a trace's {@code replayed_from}: the source was a workflow, and which of its
     * packets were played — listed unless they all were; a single one is also named on its own.
     */
    static void describe(JsonObject replayedFrom, String json, List<Integer> packets) {
        replayedFrom.addProperty("kind", "workflow");
        JsonObject root = parse(json);
        if (root != null && !str(root, "workflow_id").isBlank()) replayedFrom.addProperty("workflow_id", str(root, "workflow_id"));
        JsonArray all = packets(root);
        java.util.TreeSet<Integer> played = new java.util.TreeSet<>();
        for (int p : packets) if (p >= 0 && p < all.size() && all.get(p).isJsonObject()) played.add(p);
        if (played.isEmpty() || played.size() == all.size()) return;
        JsonArray list = new JsonArray();
        for (int p : played) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", str(all.get(p).getAsJsonObject(), "session_id"));
            entry.addProperty("title", str(all.get(p).getAsJsonObject(), "title"));
            list.add(entry);
        }
        replayedFrom.add("packets", list);
        if (played.size() == 1) {
            replayedFrom.addProperty("packet_id", list.get(0).getAsJsonObject().get("id").getAsString());
            replayedFrom.addProperty("packet_title", list.get(0).getAsJsonObject().get("title").getAsString());
        }
    }

    /**
     * Copies the workflow's classifiers from {@code <file>.files/} into {@code tempDir}, where
     * the player expects what it downloads for a certificate — a copy, because the player
     * deletes that folder when another source is opened.
     */
    static QTraceReplayEngine.DownloadResult copyClassifiers(String source, List<String> names, Path tempDir)
            throws IOException {
        Path file = Path.of(source);
        Path beside = file.resolveSibling(file.getFileName() + ".files");
        Files.createDirectories(tempDir);
        List<String> succeeded = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (String name : names) {
            // A name is a file name, never a path out of the folder.
            boolean plain = !name.contains("/") && !name.contains("\\") && !name.contains("..");
            Path from = beside.resolve(name + ".json");
            if (!plain || !Files.isRegularFile(from)) {
                failed.put(name, "not found beside the workflow (" + beside.getFileName() + ")");
                continue;
            }
            Files.copy(from, tempDir.resolve(name + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            succeeded.add(name);
        }
        return new QTraceReplayEngine.DownloadResult(succeeded, failed);
    }

    /** The packet a source asks for — {@code qtw_…#<packet id>}, as a training step gives it — or null. */
    static String packetOf(String source) {
        if (source == null) return null;
        String s = source.strip();
        int hash = s.indexOf('#');
        return s.startsWith(ID_PREFIX) && hash > 0 && hash < s.length() - 1 ? s.substring(hash + 1) : null;
    }

    /** Where the packet of that ID plays in the workflow, or -1. */
    static int packetIndex(String json, String packetId) {
        if (packetId == null) return -1;
        JsonArray packets = packets(parse(json));
        for (int i = 0; i < packets.size(); i++)
            if (packets.get(i).isJsonObject() && packetId.equals(str(packets.get(i).getAsJsonObject(), "session_id"))) return i;
        return -1;
    }

    private static JsonObject parse(String json) {
        if (json == null) return null;
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonArray packets(JsonObject root) {
        return root != null && root.has("sessions") && root.get("sessions").isJsonArray()
            ? root.getAsJsonArray("sessions") : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
