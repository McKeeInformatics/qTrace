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
import com.google.gson.JsonParser;
import io.qtrace.ObjectClassifierFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replaying with exactly the object classifier that was recorded. */
class ObjectClassifierReplayTest {

    private static final Instant T = Instant.parse("2026-11-04T10:00:00Z");
    private static final byte[] CK19_V1 = classifier("Cell: CK19 mean", 120.0);
    private static final byte[] CK19_V2 = classifier("Cell: CK19 mean", 135.5);
    private static final byte[] CD3 = classifier("Cell: CD3 mean", 80.5);

    private static byte[] classifier(String measurement, double threshold) {
        return ("{\"object_classifier_type\":\"SimpleClassifier\",\"function\":{\"classifier_fun\":\"ClassifyByMeasurementFunction\","
            + "\"measurement\":\"" + measurement + "\",\"threshold\":" + threshold
            + ",\"pathClassBelow\":{\"name\":\"N\"},\"pathClassEquals\":{\"name\":\"P\"},\"pathClassAbove\":{\"name\":\"P\"}},"
            + "\"pathClasses\":[],\"filter\":\"CELLS\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject rec(String name, byte[] raw) {
        return ObjectClassifierFiles.record(name, raw, T, "oscar", true);
    }

    /** A .qtrace whose sessions each saved the given object classifiers. */
    @SafeVarargs
    private static String qtrace(List<JsonObject>... perSession) {
        JsonObject root = new JsonObject();
        JsonArray sessions = new JsonArray();
        int i = 0;
        for (List<JsonObject> recs : perSession) {
            JsonObject s = new JsonObject();
            s.addProperty("session_id", "s" + (++i));
            s.add("steps", new JsonArray());
            JsonArray arr = new JsonArray();
            recs.forEach(arr::add);
            s.add("object_classifiers", arr);
            sessions.add(s);
        }
        root.add("sessions", sessions);
        return root.toString();
    }

    /** Writes a local record and the companion copy of each given file, as the export does. */
    private static Path local(Path dir, JsonObject rec, byte[] raw) throws Exception {
        Path q = dir.resolve("slide.png.qtrace");
        Path companions = ObjectClassifierFiles.companionDir(q);
        Files.createDirectories(companions);
        Files.write(companions.resolve(rec.get("classifier_file").getAsString()), raw);
        return q;
    }

    // ── which classifiers a replay needs ─────────────────────────────────────

    @Test
    void aReplayOfEverythingUsesTheLastSavedVersionOfEachName() {
        String q = qtrace(List.of(rec("CK19", CK19_V1), rec("CD3", CD3)), List.of(rec("CK19", CK19_V2)));
        List<JsonObject> used = ObjectClassifierReplay.inScope(q, -1);
        assertEquals(List.of("CK19", "CD3"), used.stream().map(o -> o.get("name").getAsString()).toList());
        assertEquals(rec("CK19", CK19_V2).get("sha256"), used.get(0).get("sha256"));
    }

    @Test
    void aReplayOfOneSessionUsesTheVersionsSavedUpToThatSession() {
        String q = qtrace(List.of(rec("CK19", CK19_V1)), List.of(rec("CK19", CK19_V2)));
        assertEquals(rec("CK19", CK19_V1).get("sha256"), ObjectClassifierReplay.inScope(q, 0).get(0).get("sha256"));
        assertEquals(rec("CK19", CK19_V2).get("sha256"), ObjectClassifierReplay.inScope(q, 1).get(0).get("sha256"));
    }

    @Test
    void everyDistinctVersionIsListedOnceForTheCopy() {
        String q = qtrace(List.of(rec("CK19", CK19_V1)), List.of(rec("CK19", CK19_V1), rec("CK19", CK19_V2)));
        assertEquals(2, ObjectClassifierReplay.versions(q).size());
    }

    @Test
    void aRecordWithNoObjectClassifierNeedsNone() {
        assertTrue(ObjectClassifierReplay.inScope("{\"sessions\":[{\"steps\":[]}]}", -1).isEmpty());
        assertTrue(ObjectClassifierReplay.inScope("not json", -1).isEmpty());
        assertTrue(ObjectClassifierReplay.prepare("/x/a.qtrace", "{\"sessions\":[]}", Path.of("/nonexistent")).none());
    }

    // ── the pre-flight check: the same file, or a clear warning ──────────────

    @Test
    void theExactFileIsVerifiedAndHandedToTheReplay(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path q = local(dir, r, CK19_V1);
        var res = ObjectClassifierReplay.prepare(q.toString(), qtrace(List.of(r)), dir.resolve("temp"));
        assertEquals(List.of("OC-CK19"), res.verified());
        assertTrue(res.allVerified());
        assertEquals(0, res.problems());
        assertArrayEquals(CK19_V1, Files.readAllBytes(dir.resolve("temp/object_classifiers").resolve(r.get("classifier_file").getAsString())));
    }

    @Test
    void aFileThatIsGoneIsReportedMissingWithItsName(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        var res = ObjectClassifierReplay.prepare(dir.resolve("slide.png.qtrace").toString(), qtrace(List.of(r)), dir.resolve("temp"));
        assertEquals(java.util.Set.of("OC-CK19"), res.missing().keySet());
        assertFalse(res.allVerified());
        assertEquals(1, res.problems());
        assertTrue(res.missing().get("OC-CK19").contains(r.get("classifier_file").getAsString()), res.missing().toString());
    }

    @Test
    void aFileThatWasChangedIsReportedAndNeverHandedToTheReplay(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path q = local(dir, r, CK19_V2);            // another threshold under the recorded name
        var res = ObjectClassifierReplay.prepare(q.toString(), qtrace(List.of(r)), dir.resolve("temp"));
        assertEquals(java.util.Set.of("OC-CK19"), res.differs().keySet());
        assertTrue(res.differs().get("OC-CK19").contains("sha256"));
        assertFalse(Files.exists(dir.resolve("temp/object_classifiers").resolve(r.get("classifier_file").getAsString())));
    }

    @Test
    void aRecordFromBeforeTheContentWasCapturedCannotBeReplayedExactly(@TempDir Path dir) {
        JsonObject old = new JsonObject();
        old.addProperty("name", "OldOne");
        old.addProperty("saved_at", "2026-10-01T00:00:00Z");
        var res = ObjectClassifierReplay.prepare(dir.resolve("a.qtrace").toString(), qtrace(List.of(old)), dir.resolve("temp"));
        assertEquals(java.util.Set.of("OldOne"), res.notCaptured().keySet());
        assertEquals(1, res.problems());
    }

    @Test
    void aRecordOpenedFromTheWorkspaceSaysItsFilesAreNotThereYet(@TempDir Path dir) {
        JsonObject r = rec("OC-CK19", CK19_V1);
        var res = ObjectClassifierReplay.prepare("qtc_01ABCDEF", qtrace(List.of(r)), dir.resolve("temp"));
        assertEquals(java.util.Set.of("OC-CK19"), res.missing().keySet());
        assertTrue(res.missing().get("OC-CK19").contains("workspace"), res.missing().toString());
    }

    @Test
    void anOlderVersionThatIsGoneDoesNotBlockAReplayThatUsesTheLatest(@TempDir Path dir) throws Exception {
        JsonObject v1 = rec("OC-CK19", CK19_V1), v2 = rec("OC-CK19", CK19_V2);
        Path q = local(dir, v2, CK19_V2);           // only the latest version's file is there
        var res = ObjectClassifierReplay.prepare(q.toString(), qtrace(List.of(v1), List.of(v2)), dir.resolve("temp"));
        assertTrue(res.allVerified(), res.toString());
    }

    // ── the restore step the script runs first ───────────────────────────────

    @Test
    void theScriptWritesTheRecordedFileIntoTheProjectsObjectClassifiers(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path q = local(dir, r, CK19_V1);
        String json = qtrace(List.of(r));
        Path temp = dir.resolve("temp");
        ObjectClassifierReplay.prepare(q.toString(), json, temp);

        String groovy = ObjectClassifierReplay.restoreBlocks(json, -1, temp);
        assertTrue(groovy.contains(Base64.getEncoder().encodeToString(CK19_V1)), "the exact bytes travel in the script");
        assertTrue(groovy.contains("classifiers/object_classifiers"));
        assertTrue(groovy.contains("OC-CK19.json"));
        assertFalse(groovy.contains("WARNING"));
    }

    @Test
    void aClassifierWhoseFileIsNotAvailableIsWarnedAboutInTheScript(@TempDir Path dir) {
        JsonObject r = rec("OC-CK19", CK19_V1);
        String groovy = ObjectClassifierReplay.restoreBlocks(qtrace(List.of(r)), -1, dir.resolve("empty"));
        assertTrue(groovy.contains("WARNING"), groovy);
        assertTrue(groovy.contains("OC-CK19"));
        assertTrue(groovy.contains("NOT"), "it says the replay will not use the same classifier");
    }

    @Test
    void withoutAnyObjectClassifierTheScriptHasNothingToAdd() {
        assertEquals("", ObjectClassifierReplay.restoreBlocks("{\"sessions\":[{\"steps\":[]}]}", -1, null));
    }

    @Test
    void aProjectFileOfThatNameWithOtherContentIsKeptAsABackupNotLost(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path q = local(dir, r, CK19_V1);
        String json = qtrace(List.of(r));
        ObjectClassifierReplay.prepare(q.toString(), json, dir.resolve("temp"));
        String groovy = ObjectClassifierReplay.restoreBlocks(json, -1, dir.resolve("temp"));
        assertTrue(groovy.contains("replaced-"), "the user's own file is renamed, never overwritten silently");
        assertTrue(groovy.contains("identical"), "an identical file is left alone");
    }

    // ── the pre-flight row ───────────────────────────────────────────────────

    private static ObjectClassifierReplay.Result result(List<String> ok, java.util.Map<String, String> missing,
                                                       java.util.Map<String, String> differs, java.util.Map<String, String> old) {
        return new ObjectClassifierReplay.Result(ok, missing, differs, old);
    }

    @Test
    void whenEverythingIsVerifiedTheRowIsGreenAndCountsThem() {
        var r = result(List.of("A", "B"), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        assertEquals(ObjectClassifierReplay.Level.OK, r.level());
        assertEquals("2 verified by sha256", r.summary());
    }

    @Test
    void aMissingFileIsRedAndNamed() {
        var r = result(List.of(), java.util.Map.of("A", "file not found: A@1.json"), java.util.Map.of(), java.util.Map.of());
        assertEquals(ObjectClassifierReplay.Level.BAD, r.level());
        assertEquals("1 missing: A", r.summary());
    }

    @Test
    void aChangedFileIsRedAndSaysItIsNotTheRecordedOne() {
        var r = result(List.of(), java.util.Map.of(), java.util.Map.of("A", "sha256 differs"), java.util.Map.of());
        assertEquals(ObjectClassifierReplay.Level.BAD, r.level());
        assertEquals("1 changed since it was recorded (sha256 differs): A", r.summary());
    }

    @Test
    void anOldRecordIsOnlyAWarningBecauseNothingWasEverCapturedToCompareWith() {
        var r = result(List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of("A", "old"));
        assertEquals(ObjectClassifierReplay.Level.WARN, r.level());
        assertEquals("1 recorded before qTrace kept their content, cannot be replayed exactly: A", r.summary());
    }

    @Test
    void severalProblemsAreAllListedAndTheWorstColoursTheRow() {
        var r = result(List.of("OK1"), java.util.Map.of("M", "x"), java.util.Map.of("D", "y"), java.util.Map.of("O", "z"));
        assertEquals(ObjectClassifierReplay.Level.BAD, r.level());
        assertEquals("1 missing: M; 1 changed since it was recorded (sha256 differs): D; "
            + "1 recorded before qTrace kept their content, cannot be replayed exactly: O; 1 verified by sha256", r.summary());
    }

    // ── Groovy would read a line starting with "{" after an unterminated call as that call's closure ──

    @Test
    void noGeneratedLineCanBeReadAsAClosureHandedToThePreviousCall(@TempDir Path dir) throws Exception {
        JsonObject ok = rec("OC-CK19", CK19_V1), gone = rec("OC-CD3", CD3);
        Path q = local(dir, ok, CK19_V1);
        String json = qtrace(List.of(ok, gone));
        Path temp = dir.resolve("temp");
        ObjectClassifierReplay.prepare(q.toString(), json, temp);
        for (String line : ObjectClassifierReplay.restoreBlocks(json, -1, temp).split("\n")) {
            String t = line.strip();
            assertFalse(t.startsWith("{") || t.startsWith("("), "a line must not open a block or a call: " + t);
            if (t.startsWith("println(")) assertTrue(t.endsWith(");"), "an unterminated call: " + t);
        }
    }

    @Test
    void theScriptTellsCoreTheFileComesFromAReplayBeforeWritingIt(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path q = local(dir, r, CK19_V1);
        String json = qtrace(List.of(r));
        ObjectClassifierReplay.prepare(q.toString(), json, dir.resolve("temp"));
        String groovy = ObjectClassifierReplay.restoreBlocks(json, -1, dir.resolve("temp"));
        int announce = groovy.indexOf("ObjectClassifierRestores.expect('OC-CK19', '" + r.get("sha256").getAsString() + "')");
        int write = groovy.indexOf("_f.bytes = _bytes");
        assertTrue(announce > 0, groovy);
        assertTrue(announce < write, "announced before the file is written, or the watcher wins the race");
    }

    // ── a composite carries its components: a missing standalone file is information, not a problem ──

    private static byte[] compositeOf(byte[]... parts) {
        StringBuilder sb = new StringBuilder("{\"object_classifier_type\":\"CompositeClassifier\",\"classifiers\":[");
        for (int i = 0; i < parts.length; i++) sb.append(i > 0 ? "," : "").append(new String(parts[i], StandardCharsets.UTF_8));
        return sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A record whose only step runs the given script. */
    private static String qtraceRunning(String script, List<JsonObject> recs) {
        JsonObject root = JsonParser.parseString(qtrace(recs)).getAsJsonObject();
        JsonObject step = new JsonObject();
        step.addProperty("command", "Run object classifier");
        step.addProperty("script_fragment", script);
        step.addProperty("is_scriptable", true);
        JsonArray steps = new JsonArray();
        steps.add(step);
        root.getAsJsonArray("sessions").get(0).getAsJsonObject().add("steps", steps);
        return root.toString();
    }

    @Test
    void aMissingComponentThatAVerifiedCompositeCarriesIsInformationNotAProblem(@TempDir Path dir) throws Exception {
        byte[] comp = compositeOf(CK19_V1, CD3);
        JsonObject ck = rec("OC-CK19", CK19_V1), panel = rec("OC-Panel", comp);
        Path q = local(dir, panel, comp);                              // the composite is there, the standalone CK19 file is not
        String json = qtraceRunning("runObjectClassifier(\"OC-Panel\")", List.of(ck, panel));
        var res = ObjectClassifierReplay.prepare(q.toString(), json, dir.resolve("temp"));
        assertEquals(0, res.problems(), res.toString());
        assertEquals(ObjectClassifierReplay.Level.OK, res.level());
        assertTrue(res.embedded().get("OC-CK19").contains("OC-Panel"), res.embedded().toString());
        assertTrue(res.summary().contains("embedded in OC-Panel"), res.summary());
    }

    @Test
    void aMissingComponentThatAStepRunsByNameIsStillAProblem(@TempDir Path dir) throws Exception {
        byte[] comp = compositeOf(CK19_V1, CD3);
        JsonObject ck = rec("OC-CK19", CK19_V1), panel = rec("OC-Panel", comp);
        Path q = local(dir, panel, comp);
        String json = qtraceRunning("runObjectClassifier(\"OC-CK19\")", List.of(ck, panel));
        var res = ObjectClassifierReplay.prepare(q.toString(), json, dir.resolve("temp"));
        assertEquals(java.util.Set.of("OC-CK19"), res.missing().keySet());
        assertEquals(ObjectClassifierReplay.Level.BAD, res.level());
    }

    @Test
    void aMissingCompositeLeavesItsMissingComponentAProblemToo(@TempDir Path dir) {
        byte[] comp = compositeOf(CK19_V1, CD3);
        JsonObject ck = rec("OC-CK19", CK19_V1), panel = rec("OC-Panel", comp);
        var res = ObjectClassifierReplay.prepare(dir.resolve("slide.png.qtrace").toString(), qtraceRunning("x()", List.of(ck, panel)), dir.resolve("temp"));
        assertEquals(java.util.Set.of("OC-CK19", "OC-Panel"), res.missing().keySet());
        assertTrue(res.embedded().isEmpty());
    }

    @Test
    void aComponentWithAnotherThresholdIsNotTheOneTheCompositeCarries(@TempDir Path dir) throws Exception {
        byte[] comp = compositeOf(CK19_V2, CD3);                        // the composite carries 135.5
        JsonObject ck = rec("OC-CK19", CK19_V1), panel = rec("OC-Panel", comp);   // the standalone is 120
        Path q = local(dir, panel, comp);
        var res = ObjectClassifierReplay.prepare(q.toString(), qtraceRunning("x()", List.of(ck, panel)), dir.resolve("temp"));
        assertEquals(java.util.Set.of("OC-CK19"), res.missing().keySet());
    }

    @Test
    void theScriptSaysANonRestoredComponentIsCarriedByTheCompositeInsteadOfWarning(@TempDir Path dir) throws Exception {
        byte[] comp = compositeOf(CK19_V1, CD3);
        JsonObject ck = rec("OC-CK19", CK19_V1), panel = rec("OC-Panel", comp);
        Path q = local(dir, panel, comp);
        String json = qtraceRunning("runObjectClassifier(\"OC-Panel\")", List.of(ck, panel));
        ObjectClassifierReplay.prepare(q.toString(), json, dir.resolve("temp"));
        String groovy = ObjectClassifierReplay.restoreBlocks(json, -1, dir.resolve("temp"));
        assertFalse(groovy.contains("WARNING"), groovy);
        assertTrue(groovy.contains("OC-CK19") && groovy.contains("embedded in OC-Panel"), groovy);
    }

    // ── a record opened by its ID: the files come from the workspace, into a folder ──

    private static Path downloaded(Path dir, JsonObject rec, byte[] raw) throws Exception {
        Path d = dir.resolve("downloaded");
        Files.createDirectories(d);
        Files.write(d.resolve(rec.get("classifier_file").getAsString()), raw);
        return d;
    }

    @Test
    void theFilesDownloadedForARecordOpenedByItsIdAreCheckedLikeThoseBesideALocalOne(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path d = downloaded(dir, r, CK19_V1);
        var res = ObjectClassifierReplay.prepare("qtc_01ABCDEF", qtrace(List.of(r)), dir.resolve("temp"), d, null);
        assertEquals(List.of("OC-CK19"), res.verified());
        assertTrue(res.allVerified());
        assertArrayEquals(CK19_V1, Files.readAllBytes(dir.resolve("temp/object_classifiers").resolve(r.get("classifier_file").getAsString())));
    }

    @Test
    void aDownloadedFileThatIsNotTheRecordedOneIsRefusedAndNeverReplayed(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path d = downloaded(dir, r, CK19_V2);
        var res = ObjectClassifierReplay.prepare("qtc_01ABCDEF", qtrace(List.of(r)), dir.resolve("temp"), d, null);
        assertEquals(java.util.Set.of("OC-CK19"), res.differs().keySet());
        assertFalse(Files.exists(dir.resolve("temp/object_classifiers").resolve(r.get("classifier_file").getAsString())));
    }

    @Test
    void aFileTheWorkspaceDidNotGiveIsMissing(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Files.createDirectories(dir.resolve("downloaded"));
        var res = ObjectClassifierReplay.prepare("qtc_01ABCDEF", qtrace(List.of(r)), dir.resolve("temp"), dir.resolve("downloaded"), null);
        assertEquals(java.util.Set.of("OC-CK19"), res.missing().keySet());
    }

    @Test
    void aDownloadThatFailedSaysWhyForEveryClassifier(@TempDir Path dir) {
        JsonObject r = rec("OC-CK19", CK19_V1);
        var res = ObjectClassifierReplay.prepare("qtc_01ABCDEF", qtrace(List.of(r)), dir.resolve("temp"), null, "download failed: HTTP 502");
        assertTrue(res.missing().get("OC-CK19").contains("download failed: HTTP 502"), res.missing().toString());
    }

    @Test
    void aLocalRecordStillLooksBesideItselfWhenGivenItsOwnFolder(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path q = local(dir, r, CK19_V1);
        var res = ObjectClassifierReplay.prepare(q.toString(), qtrace(List.of(r)), dir.resolve("temp"),
            io.qtrace.ObjectClassifierFiles.companionDir(q), null);
        assertTrue(res.allVerified());
    }

    // ── resolve: beside a local record, or downloaded for one opened by its ID ──

    @Test
    void aLocalRecordLooksBesideItselfAndDownloadsNothing(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        Path q = local(dir, r, CK19_V1);
        var res = ObjectClassifierReplay.resolve(q.toString(), qtrace(List.of(r)), dir.resolve("temp"), null,
            (id, d) -> { throw new AssertionError("a local record downloads nothing"); });
        assertTrue(res.allVerified());
    }

    @Test
    void aRecordOpenedByItsIdHasItsFilesDownloadedThenChecked(@TempDir Path dir) throws Exception {
        JsonObject r = rec("OC-CK19", CK19_V1);
        String[] asked = new String[1];
        var res = ObjectClassifierReplay.resolve("qtc_01ABCDEF", qtrace(List.of(r)), dir.resolve("temp"), "qtc_01ABCDEF", (id, d) -> {
            asked[0] = id;
            Files.createDirectories(d);
            Files.write(d.resolve(r.get("classifier_file").getAsString()), CK19_V1);
            return new QTraceReplayEngine.DownloadResult(List.of(r.get("classifier_file").getAsString()), java.util.Map.of());
        });
        assertEquals("qtc_01ABCDEF", asked[0]);
        assertTrue(res.allVerified(), res.toString());
    }

    @Test
    void aDownloadThatCannotBeDoneSaysWhyInsteadOfAnEmptyAnswer(@TempDir Path dir) {
        JsonObject r = rec("OC-CK19", CK19_V1);
        var res = ObjectClassifierReplay.resolve("qtc_01ABCDEF", qtrace(List.of(r)), dir.resolve("temp"), "qtc_01ABCDEF",
            (id, d) -> { throw new java.io.IOException("HTTP 502"); });
        assertEquals(java.util.Set.of("OC-CK19"), res.missing().keySet());
        assertTrue(res.missing().get("OC-CK19").contains("download failed: HTTP 502"), res.missing().toString());
        assertEquals(ObjectClassifierReplay.Level.BAD, res.level());
    }

    @Test
    void aRecordWithoutAnIdCannotBeDownloaded(@TempDir Path dir) {
        JsonObject r = rec("OC-CK19", CK19_V1);
        var res = ObjectClassifierReplay.resolve("https://example.org/x", qtrace(List.of(r)), dir.resolve("temp"), null,
            (id, d) -> { throw new AssertionError("nothing to ask for"); });
        assertEquals(java.util.Set.of("OC-CK19"), res.missing().keySet());
    }

    @Test
    void aRecordThatNeedsNoClassifierDownloadsNothing(@TempDir Path dir) {
        var res = ObjectClassifierReplay.resolve("qtc_01ABCDEF", "{\"sessions\":[{\"steps\":[]}]}", dir.resolve("temp"), "qtc_01ABCDEF",
            (id, d) -> { throw new AssertionError("nothing to fetch"); });
        assertTrue(res.none());
    }
}
