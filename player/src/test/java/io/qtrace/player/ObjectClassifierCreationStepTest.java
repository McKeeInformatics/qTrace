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

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import io.qtrace.ObjectClassifierFiles;
import io.qtrace.ObjectClassifierRestores;
import io.qtrace.chain.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The creation of an object classifier is a step of the replay, at its place in time: before the instruction that
 * runs it, and it writes the recorded file only when the project does not already hold that exact file.
 */
class ObjectClassifierCreationStepTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final byte[] V1 = classifier(120.0);
    private static final byte[] V2 = classifier(135.5);

    private static byte[] classifier(double threshold) {
        return ("{\"object_classifier_type\":\"SimpleClassifier\",\"function\":{\"classifier_fun\":\"ClassifyByMeasurementFunction\","
            + "\"measurement\":\"Cell: CK19 mean\",\"threshold\":" + threshold
            + ",\"pathClassBelow\":{\"name\":\"N\"},\"pathClassEquals\":{\"name\":\"P\"},\"pathClassAbove\":{\"name\":\"P\"}},"
            + "\"pathClasses\":[],\"filter\":\"CELLS\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static Instant t(int minutes) { return T0.plusSeconds(60L * minutes); }

    private static JsonObject saved(String name, byte[] raw, int minute) {
        return ObjectClassifierFiles.record(name, raw, t(minute), "oscar", true);
    }

    private static JsonObject step(String command, String script, int minute) {
        JsonObject s = new JsonObject();
        s.addProperty("command", command);
        s.addProperty("script_fragment", script);
        s.addProperty("is_scriptable", true);
        s.addProperty("timestamp", t(minute).toString());
        return s;
    }

    private static final String DETECT = "runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', '{}')";
    private static String run(String name) { return "runObjectClassifier(\"" + name + "\")"; }

    /** One session: its steps and the object classifiers it saved. */
    private record Session(List<JsonObject> steps, List<JsonObject> classifiers) {}

    private static Session session(List<JsonObject> steps, JsonObject... classifiers) {
        return new Session(steps, List.of(classifiers));
    }

    private static String qtrace(Session... sessions) {
        JsonObject root = new JsonObject();
        root.add("image", new JsonObject());
        JsonArray arr = new JsonArray();
        int i = 0;
        for (Session s : sessions) {
            JsonObject o = new JsonObject();
            o.addProperty("session_id", "session-" + (++i));
            JsonArray steps = new JsonArray();
            s.steps().forEach(steps::add);
            o.add("steps", steps);
            JsonArray oc = new JsonArray();
            s.classifiers().forEach(oc::add);
            o.add("object_classifiers", oc);
            arr.add(o);
        }
        root.add("sessions", arr);
        return root.toString();
    }

    private static List<String> titles(List<ReplayStep> steps) {
        List<String> out = new ArrayList<>();
        for (ReplayStep s : steps) out.add(s.title());
        return out;
    }

    // ── where the step stands ────────────────────────────────────────────────

    @Test
    void theCreationStandsBetweenTheInstructionsAtTheTimeItWasSaved() {
        String json = qtrace(session(List.of(step("Cell detection", DETECT, 1), step("Run object classifier", run("OC"), 3)),
            saved("OC", V1, 2)));
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(json, -1);

        assertEquals(List.of("Cell detection", "Create object classifier: OC", "Run object classifier: OC"), titles(steps));
        ReplayStep create = steps.get(1);
        assertEquals(2, create.index(), "numbered with the others");
        assertEquals(3, steps.get(2).index());
        assertNotNull(create.objectClassifierFile());
        assertEquals("OC", create.objectClassifierFile().name());
        assertEquals(Hashing.sha256Hex(V1), create.objectClassifierFile().sha256());
        assertTrue(create.objectClassifierFile().needed());
        assertTrue(create.isExecutable());
        assertEquals("Object Classifier", create.type());
        assertNull(steps.get(0).objectClassifierFile(), "an instruction is not a creation");
    }

    @Test
    void aSaveAndApplyWritesTheFileAfterTheRunIsRecordedAndTheCreationStillComesFirst() {
        // QuPath's Save + Apply records the run, then writes the file a fraction of a second later
        JsonObject runStep = step("Run object classifier", run("OC"), 3);
        JsonObject rec = ObjectClassifierFiles.record("OC", V1, t(3).plusMillis(282), "oscar", true);
        String json = qtrace(session(List.of(step("Cell detection", DETECT, 1), runStep), rec));
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(json, -1);

        assertEquals(List.of("Cell detection", "Create object classifier: OC", "Run object classifier: OC"), titles(steps));
        assertTrue(steps.get(1).objectClassifierFile().needed());
        assertTrue(steps.get(1).isExecutable());
    }

    @Test
    void aClassifierNoInstructionRunsIsListedButNotRun() {
        String json = qtrace(session(List.of(step("Cell detection", DETECT, 1)), saved("OC", V1, 2)));
        ReplayStep create = QTraceReplayEngine.extractSteps(json, -1).get(1);

        assertEquals("Create object classifier: OC", create.title());
        assertFalse(create.objectClassifierFile().needed());
        assertFalse(create.isExecutable(), "nothing uses it: the replay writes nothing");
    }

    @Test
    void anInstructionTakenOutOfTheReplayDoesNotMakeTheCreationNeeded() {
        JsonObject runStep = step("Run object classifier", run("OC"), 3);
        runStep.addProperty("replay_skip", true);
        String json = qtrace(session(List.of(step("Cell detection", DETECT, 1), runStep), saved("OC", V1, 2)));
        ReplayStep create = QTraceReplayEngine.extractSteps(json, -1).get(1);
        assertFalse(create.isExecutable());
    }

    @Test
    void eachVersionOfANameIsAStepAtItsTimeAndEachRunUsesTheOneBeforeIt() {
        String json = qtrace(
            session(List.of(step("Run object classifier", run("OC") + ";", 2)), saved("OC", V1, 1)),
            session(List.of(step("Run object classifier", run("OC"), 4)), saved("OC", V2, 3)));
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(json, -1);

        assertEquals(List.of("Create object classifier: OC (v1/2)", "Run object classifier: OC",
            "Create object classifier: OC (v2/2)", "Run object classifier: OC"), titles(steps));
        assertEquals(Hashing.sha256Hex(V1), steps.get(0).objectClassifierFile().sha256());
        assertEquals(Hashing.sha256Hex(V2), steps.get(2).objectClassifierFile().sha256());
        assertTrue(steps.get(0).isExecutable());
        assertTrue(steps.get(2).isExecutable());
    }

    @Test
    void aVersionReplacedBeforeAnyInstructionRanItIsNotNeeded() {
        // The same instruction run in both sessions is one instruction, at its last occurrence
        String json = qtrace(
            session(List.of(step("Run object classifier", run("OC"), 2)), saved("OC", V1, 1)),
            session(List.of(step("Run object classifier", run("OC"), 4)), saved("OC", V2, 3)));
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(json, -1);

        assertEquals(3, steps.size(), titles(steps).toString());
        assertFalse(steps.get(0).isExecutable(), "version 1: replaced before the instruction that runs");
        assertTrue(steps.get(1).isExecutable());
        assertEquals(Hashing.sha256Hex(V2), steps.get(1).objectClassifierFile().sha256());
        assertEquals("Run object classifier: OC", steps.get(2).title());
    }

    @Test
    void aSessionReplayedAloneCreatesTheClassifierAnEarlierSessionSavedWhenItRunsIt() {
        String json = qtrace(
            session(List.of(step("Cell detection", DETECT, 1)), saved("OC", V1, 2), saved("UNUSED", V2, 2)),
            session(List.of(step("Run object classifier", run("OC"), 5))));
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(json, 1);

        assertEquals(List.of("Create object classifier: OC", "Run object classifier: OC"), titles(steps),
            "only the classifier this session runs comes from the earlier one");
        assertTrue(steps.get(0).isExecutable());
    }

    @Test
    void aClassifierSavedWithoutATimeIsCreatedBeforeTheFirstInstruction() {
        JsonObject rec = saved("OC", V1, 2);
        rec.remove("saved_at");
        String json = qtrace(session(List.of(step("Cell detection", DETECT, 1), step("Run object classifier", run("OC"), 3)), rec));
        assertEquals("Create object classifier: OC", QTraceReplayEngine.extractSteps(json, -1).get(0).title());
    }

    @Test
    void aRecordFromBeforeTheContentWasKeptHasNoCreationStepAndKeepsItsWarning() {
        JsonObject old = new JsonObject();
        old.addProperty("name", "OC");
        old.addProperty("saved_at", t(2).toString());
        String json = qtrace(session(List.of(step("Run object classifier", run("OC"), 3)), old));

        assertEquals(List.of("Run object classifier: OC"), titles(QTraceReplayEngine.extractSteps(json, -1)));
        String preamble = QTraceReplayEngine.buildPreambleScript(json, null, -1);
        assertTrue(preamble.contains("WARNING: object classifier \"OC\" cannot be restored"), preamble);
    }

    @Test
    void theValidatedOnlyReplayKeepsTheCreationWithTheInstructionThatNeedsIt() {
        String json = qtrace(session(List.of(step("Run object classifier", run("OC"), 3)), saved("OC", V1, 2)));
        // nothing is stamped: validated-only leaves out both, never the instruction without its classifier
        assertTrue(QTraceReplayEngine.extractSteps(json, -1, true).isEmpty());
    }

    // ── the preamble only carries the verified copies; it writes nothing ──────

    private static Path localRecord(Path dir, JsonObject rec, byte[] raw) throws Exception {
        Path q = dir.resolve("slide.png.qtrace");
        Files.createDirectories(ObjectClassifierFiles.companionDir(q));
        if (raw != null) Files.write(ObjectClassifierFiles.companionDir(q).resolve(rec.get("classifier_file").getAsString()), raw);
        return q;
    }

    @Test
    void thePreambleCarriesTheVerifiedCopyAndWritesNothingIntoTheProject(@TempDir Path dir) throws Exception {
        JsonObject rec = saved("OC", V1, 2);
        String json = qtrace(session(List.of(step("Run object classifier", run("OC"), 3)), rec));
        Path temp = dir.resolve("temp");
        ObjectClassifierReplay.prepare(localRecord(dir, rec, V1).toString(), json, temp);

        String preamble = QTraceReplayEngine.buildPreambleScript(json, temp, -1);
        assertTrue(preamble.contains(Base64.getEncoder().encodeToString(V1)), "the copy travels with the script");
        assertFalse(preamble.contains("classifiers/object_classifiers"), "the preamble never touches the project:\n" + preamble);
        assertFalse(preamble.contains("WARNING"));
    }

    @Test
    void thePreambleSaysWhyACopyCannotBeUsed(@TempDir Path dir) throws Exception {
        JsonObject rec = saved("OC", V1, 2);
        String json = qtrace(session(List.of(step("Run object classifier", run("OC"), 3)), rec));
        Path temp = dir.resolve("temp");
        ObjectClassifierReplay.prepare(localRecord(dir, rec, V2).toString(), json, temp); // another content under its name

        String preamble = QTraceReplayEngine.buildPreambleScript(json, temp, -1);
        assertFalse(preamble.contains(Base64.getEncoder().encodeToString(V2)), "a copy that is not the recorded one never travels");
        assertTrue(preamble.contains("sha256 differs"), preamble);
    }

    @Test
    void theWholeScriptCompilesAndCreatesBeforeTheRun(@TempDir Path dir) throws Exception {
        JsonObject rec = saved("OC", V1, 2);
        String json = qtrace(session(List.of(step("Cell detection", DETECT, 1), step("Run object classifier", run("OC"), 3)), rec));
        Path temp = dir.resolve("temp");
        ObjectClassifierReplay.prepare(localRecord(dir, rec, V1).toString(), json, temp);

        String script = QTraceReplayEngine.generateGroovyScript(json, temp, -1);
        new GroovyShell().parse(script);   // compiles
        int create = script.indexOf("Step 2: Create object classifier: OC");
        assertTrue(script.indexOf("Step 1: Cell detection") < create && create < script.indexOf("Step 3: Run object classifier: OC"), script);
    }

    // ── what the step does in the project ────────────────────────────────────

    /** Runs a creation step in a plain shell whose getProject() is a project in {@code project}. */
    private static Object runCreation(Path project, String name, byte[] recorded, String copyB64, String problem) {
        Binding b = new Binding();
        groovy.util.Expando proj = new groovy.util.Expando();
        proj.setProperty("getPath", new groovy.lang.Closure<Path>(null) {
            @SuppressWarnings("unused") public Path doCall() { return project.resolve("project.qpproj"); }
        });
        b.setVariable("getProject", new groovy.lang.Closure<Object>(null) {
            @SuppressWarnings("unused") public Object doCall() { return proj; }
        });
        String key = name + "@" + Hashing.sha256Hex(recorded);
        java.util.Map<String, String> copies = new java.util.HashMap<>(), problems = new java.util.HashMap<>();
        if (copyB64 != null) copies.put(key, copyB64);
        if (problem != null) problems.put(key, problem);
        b.setVariable("_qtraceObjectClassifiers", copies);
        b.setVariable("_qtraceObjectClassifierProblems", problems);
        return new GroovyShell(b).evaluate(ObjectClassifierReplay.creationFragment(name, Hashing.sha256Hex(recorded)));
    }

    private static Path file(Path project, String name) { return project.resolve("classifiers/object_classifiers/" + name + ".json"); }

    private static List<String> backups(Path project) throws Exception {
        try (var s = Files.list(project.resolve("classifiers/object_classifiers"))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.contains(".replaced-")).toList();
        }
    }

    @Test
    void aClassifierTheProjectHasNotGotIsWrittenFromTheRecordsCopy(@TempDir Path project) throws Exception {
        Object note = runCreation(project, "OC", V1, Base64.getEncoder().encodeToString(V1), null);
        assertArrayEquals(V1, Files.readAllBytes(file(project, "OC")));
        assertTrue(String.valueOf(note).startsWith("created"), String.valueOf(note));
        assertTrue(ObjectClassifierRestores.consume("OC", Hashing.sha256Hex(V1)), "Core was told it is a replay's file, not a save");
    }

    @Test
    void theExactFileAlreadyInTheProjectIsLeftAlone(@TempDir Path project) throws Exception {
        Files.createDirectories(file(project, "OC").getParent());
        Files.write(file(project, "OC"), V1);
        Files.setLastModifiedTime(file(project, "OC"), java.nio.file.attribute.FileTime.from(T0));

        Object note = runCreation(project, "OC", V1, Base64.getEncoder().encodeToString(V1), null);
        assertTrue(String.valueOf(note).startsWith("already in the project"), String.valueOf(note));
        assertEquals(T0, Files.getLastModifiedTime(file(project, "OC")).toInstant(), "nothing written");
        assertTrue(backups(project).isEmpty());
    }

    @Test
    void theExactFileAlreadyInTheProjectNeedsNoCopy(@TempDir Path project) throws Exception {
        Files.createDirectories(file(project, "OC").getParent());
        Files.write(file(project, "OC"), V1);
        Object note = runCreation(project, "OC", V1, null, "file not found: OC@x.json");
        assertTrue(String.valueOf(note).startsWith("already in the project"));
    }

    @Test
    void aDifferentFileOfThatNameIsKeptAsABackupThenReplaced(@TempDir Path project) throws Exception {
        Files.createDirectories(file(project, "OC").getParent());
        Files.write(file(project, "OC"), V2);

        Object note = runCreation(project, "OC", V1, Base64.getEncoder().encodeToString(V1), null);
        assertArrayEquals(V1, Files.readAllBytes(file(project, "OC")));
        List<String> kept = backups(project);
        assertEquals(1, kept.size());
        assertArrayEquals(V2, Files.readAllBytes(file(project, "OC").resolveSibling(kept.get(0))), "never overwritten silently");
        assertTrue(String.valueOf(note).startsWith("replaced"), String.valueOf(note));
        assertTrue(String.valueOf(note).contains(kept.get(0)), "the note names the backup");
    }

    @Test
    void withoutAUsableCopyTheStepFailsAndSaysWhy(@TempDir Path project) {
        Exception e = assertThrows(Exception.class,
            () -> runCreation(project, "OC", V1, null, "file not found: OC@1234.json"));
        assertTrue(e.getMessage().contains("OC"), e.getMessage());
        assertTrue(e.getMessage().contains("file not found: OC@1234.json"), e.getMessage());
        assertTrue(e.getMessage().contains("will not use another classifier"), e.getMessage());
        assertFalse(Files.exists(file(project, "OC")));
    }

    @Test
    void aDifferentFileInTheProjectIsNeverReplacedWithoutTheCopy(@TempDir Path project) throws Exception {
        Files.createDirectories(file(project, "OC").getParent());
        Files.write(file(project, "OC"), V2);
        assertThrows(Exception.class, () -> runCreation(project, "OC", V1, null, null));
        assertArrayEquals(V2, Files.readAllBytes(file(project, "OC")), "left as it was");
    }

    @Test
    void aCopyThatIsNotTheRecordedFileIsRefused(@TempDir Path project) {
        Exception e = assertThrows(Exception.class,
            () -> runCreation(project, "OC", V1, Base64.getEncoder().encodeToString(V2), null));
        assertTrue(e.getMessage().contains("sha256"), e.getMessage());
        assertFalse(Files.exists(file(project, "OC")));
    }

    // ── the pre-flight check knows every version a replay needs ──────────────

    @Test
    void anOlderVersionThatAnInstructionNeedsIsReportedMissing(@TempDir Path dir) throws Exception {
        JsonObject v1 = saved("OC", V1, 1), v2 = saved("OC", V2, 3);
        String json = qtrace(
            session(List.of(step("Run object classifier", run("OC") + ";", 2)), v1),
            session(List.of(step("Run object classifier", run("OC"), 4)), v2));
        Path q = localRecord(dir, v2, V2);   // only the latest copy is there

        ObjectClassifierReplay.Result res = ObjectClassifierReplay.prepare(q.toString(), json, dir.resolve("temp"));
        assertEquals(ObjectClassifierReplay.Level.BAD, res.level(), res.summary());
        assertTrue(res.summary().contains("missing: OC"), res.summary());
    }
}
