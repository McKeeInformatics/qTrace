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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A workflow (.qtflow, written by the workflow editor module) opened in the replay player. */
class WorkflowSourceTest {

    private static final String WORKFLOW = """
        { "kind": "workflow", "title": "IMC pipeline", "sessions": [
          { "session_id": "packet-1", "title": "01 Create training dataset", "validation": null,
            "steps": [ { "command": "A", "is_scriptable": true, "script_fragment": "a()" } ] },
          { "session_id": "packet-2", "title": "02 Train tissue classifier", "validation": null,
            "steps": [ { "command": "B", "is_scriptable": true, "script_fragment": "b()" },
                       { "command": "C", "is_scriptable": true, "script_fragment": "c()" } ] } ] }""";

    private static final String RECORD = """
        { "qtrace_format": "1.0", "sessions": [ { "session_id": "s1", "steps": [] } ] }""";

    @Test
    void aWorkflowIsToldFromARecord() {
        assertTrue(WorkflowSource.isWorkflow(WORKFLOW));
        assertFalse(WorkflowSource.isWorkflow(RECORD));
        assertFalse(WorkflowSource.isWorkflow("not json"));
        assertFalse(WorkflowSource.isWorkflow(null));
    }

    @Test
    void packetsAreListedInPlayOrder() {
        assertEquals(List.of("#1 — 01 Create training dataset", "#2 — 02 Train tissue classifier"),
            WorkflowSource.packetLabels(WORKFLOW));
        assertEquals("IMC pipeline", WorkflowSource.title(WORKFLOW));
    }

    @Test
    void theReplayRunsOnePacketOrAll() {
        assertEquals(1, QTraceReplayEngine.extractSteps(WORKFLOW, 0).size());
        assertEquals(2, QTraceReplayEngine.extractSteps(WORKFLOW, 1).size());
        List<ReplayStep> all = QTraceReplayEngine.extractSteps(WORKFLOW, -1);
        assertEquals(List.of("a()", "b()", "c()"), all.stream().map(ReplayStep::groovyFragment).toList());
        assertTrue(all.stream().allMatch(ReplayStep::isExecutable));
    }

    @Test
    void aReplayNamesTheWorkflowAndThePacketsItPlayed() {
        var one = new com.google.gson.JsonObject();
        WorkflowSource.describe(one, WORKFLOW, List.of(1));
        assertEquals("workflow", one.get("kind").getAsString());
        assertEquals("packet-2", one.get("packet_id").getAsString());
        assertEquals("02 Train tissue classifier", one.get("packet_title").getAsString());
        assertEquals(1, one.getAsJsonArray("packets").size());

        var whole = new com.google.gson.JsonObject();
        WorkflowSource.describe(whole, WORKFLOW, List.of(0, 1));
        assertEquals("workflow", whole.get("kind").getAsString());
        assertFalse(whole.has("packet_id"), "one packet only is named that way");
        assertFalse(whole.has("packets"), "all of them: nothing to list");

        var some = new com.google.gson.JsonObject();
        WorkflowSource.describe(some, THREE, List.of(2, 0));
        assertEquals(List.of("p1", "p3"), some.getAsJsonArray("packets").asList().stream()
            .map(e -> e.getAsJsonObject().get("id").getAsString()).toList());
    }

    // ── The playlist of the packets chosen in the player ──────────────────────────

    private static final String THREE = """
        { "kind": "workflow", "title": "W", "sessions": [
          { "session_id": "p1", "title": "One", "validation": null, "steps": [
              { "command": "Set image type", "is_scriptable": true, "script_fragment": "setImageType('FLUORESCENCE')" },
              { "command": "Reset", "is_scriptable": true, "script_fragment": "resetSelection()" } ] },
          { "session_id": "p2", "title": "Two", "validation": null, "steps": [
              { "command": "Detect cells", "is_scriptable": true, "script_fragment": "detect()" } ] },
          { "session_id": "p3", "title": "Three", "validation": null, "steps": [
              { "command": "Reset again", "is_scriptable": true, "script_fragment": "resetSelection()" },
              { "command": "Brush", "is_scriptable": false } ] } ] }""";

    @Test
    void thePlaylistFollowsThePacketsChosenInPlayOrder() {
        List<WorkflowSource.PacketStep> all = WorkflowSource.playlist(THREE, List.of(2, 0, 1));
        assertEquals(List.of(0, 0, 1, 2), all.stream().map(WorkflowSource.PacketStep::packet).toList());
        assertEquals(List.of(1, 2, 3, 4), all.stream().map(p -> p.step().index()).toList(), "numbered through the whole playlist");
        assertEquals(List.of(0, 2), WorkflowSource.playlist(THREE, List.of(2, 0)).stream()
            .map(WorkflowSource.PacketStep::packet).distinct().toList());
        assertTrue(WorkflowSource.playlist(THREE, List.of()).isEmpty());
        assertTrue(WorkflowSource.playlist(THREE, List.of(7, -1)).isEmpty());
    }

    @Test
    void anInstructionIsShownUnderTheTitleItsAuthorGaveIt() {
        List<WorkflowSource.PacketStep> all = WorkflowSource.playlist(THREE, List.of(0, 1));
        assertEquals(List.of("Set image type", "Reset", "Detect cells"), all.stream().map(p -> p.step().title()).toList());
        assertEquals("detect()", all.get(2).step().groovyFragment());
    }

    @Test
    void everyPacketPlaysAllItsInstructions() {
        // The same script in two packets is two instructions of the workflow: each packet runs its own.
        List<WorkflowSource.PacketStep> all = WorkflowSource.playlist(THREE, List.of(0, 1, 2));
        assertEquals(2, all.stream().filter(p -> p.step().groovyFragment().equals("resetSelection()")).count());
        assertEquals("Reset again", all.get(3).step().title());
    }

    @Test
    void packetTitlesAreKnownByTheirPlace() {
        assertEquals(List.of("One", "Two", "Three"), WorkflowSource.packetTitles(THREE));
    }

    @Test
    void classifiersAreCopiedFromBesideTheWorkflow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pipeline.qtflow");
        Files.writeString(file, WORKFLOW);
        Path beside = Files.createDirectories(dir.resolve("pipeline.qtflow.files"));
        Files.writeString(beside.resolve("tissue.json"), "{}");
        Path temp = dir.resolve("temp");

        QTraceReplayEngine.DownloadResult r =
            WorkflowSource.copyClassifiers(file.toString(), List.of("tissue", "regions", "../x"), temp);

        assertEquals(List.of("tissue"), r.succeeded());
        assertEquals(java.util.Set.of("regions", "../x"), r.failed().keySet());
        assertTrue(Files.exists(temp.resolve("tissue.json")));
        assertTrue(Files.exists(beside.resolve("tissue.json")), "the author's folder is left as it is");
    }

    @Test
    void withoutTheFolderEveryClassifierIsMissing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("pipeline.qtflow");
        Files.writeString(file, WORKFLOW);
        Map<String, String> failed = WorkflowSource.copyClassifiers(file.toString(), List.of("tissue"), dir.resolve("t")).failed();
        assertEquals(java.util.Set.of("tissue"), failed.keySet());
    }
    // ── A workflow published on qtrace.ca, opened by its ID ───────────────────────

    @Test
    void aPublishedWorkflowIsNamedByItsId() {
        RecordSource s = RecordSource.parse(" qtw_01ABC ");
        assertEquals("qtw_01ABC", s.certId());
        assertFalse(s.local());
        assertNull(s.token());
    }

    @Test
    void anIdCanNameThePacketToPlay() {
        assertEquals("qtw_01ABC", RecordSource.parse("qtw_01ABC#packet-2").certId());
        assertEquals("packet-2", WorkflowSource.packetOf("qtw_01ABC#packet-2"));
        assertNull(WorkflowSource.packetOf("qtw_01ABC"));
        assertNull(WorkflowSource.packetOf("/home/me/a#b.qtrace"), "only a workflow ID carries a packet");
        assertEquals(1, WorkflowSource.packetIndex(WORKFLOW, "packet-2"));
        assertEquals(-1, WorkflowSource.packetIndex(WORKFLOW, "nope"));
        assertEquals(-1, WorkflowSource.packetIndex(WORKFLOW, null));
    }

    @Test
    void itsFileAndClassifiersComeFromTheWorkflowRoutes() {
        String base = "https://www.qtrace.ca";
        assertEquals(base + "/api/workflows/qtw_01ABC/qtflow", RecordSource.contentUrl(base, "qtw_01ABC", "qtrace", null));
        assertEquals(base + "/api/workflows/qtw_01ABC/classifiers", RecordSource.contentUrl(base, "qtw_01ABC", "classifiers", null));
        assertEquals(base + "/api/verify/qtc_01ABC/qtrace", RecordSource.contentUrl(base, "qtc_01ABC", "qtrace", null));
    }

    @Test
    void aReplayOfAPublishedWorkflowNamesIt() {
        var from = new com.google.gson.JsonObject();
        WorkflowSource.describe(from, WORKFLOW.replace("\"kind\": \"workflow\",", "\"kind\": \"workflow\", \"workflow_id\": \"qtw_01ABC\","), List.of(0, 1));
        assertEquals("qtw_01ABC", from.get("workflow_id").getAsString());
    }
}
