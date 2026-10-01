package io.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySkipTest {

    private static final Instant AT = Instant.parse("2026-09-30T14:02:00Z");
    private static final String DETECT = "runPlugin('CellDetection', '{}')";
    private static final String TYPE   = "setImageType('FLUORESCENCE')";

    @Test
    void aStepIsReplayedUnlessMarked() {
        JsonObject st = step("Cell detection", DETECT);
        assertFalse(ReplaySkip.isSkipped(st));
        assertTrue(ReplaySkip.isReplayable(st));
    }

    @Test
    void markingKeepsTheStepAndRecordsWhoAndWhen() {
        List<JsonObject> steps = List.of(step("Set image type", TYPE), step("Cell detection", DETECT));
        assertEquals(1, ReplaySkip.set(steps, DETECT, true, "Bob", AT));
        assertFalse(ReplaySkip.isSkipped(steps.get(0)));
        assertTrue(ReplaySkip.isSkipped(steps.get(1)));
        assertEquals("Bob", steps.get(1).get("replay_skip_by").getAsString());
        assertEquals(AT.toString(), steps.get(1).get("replay_skip_at").getAsString());
        assertEquals(DETECT, steps.get(1).get("script_fragment").getAsString());
    }

    @Test
    void puttingAStepBackLeavesNoTrace() {
        JsonObject st = step("Cell detection", DETECT);
        JsonObject before = st.deepCopy();
        ReplaySkip.set(List.of(st), DETECT, true, "Bob", AT);
        assertEquals(1, ReplaySkip.set(List.of(st), DETECT, false, "Alice", AT));
        assertEquals(before, st);
    }

    @Test
    void everyOccurrenceOfTheInstructionFollows() {
        List<JsonObject> steps = List.of(step("Cell detection", DETECT), step("x", TYPE),
                                         step("Cell detection", "  " + DETECT + "\n"));
        assertEquals(2, ReplaySkip.set(steps, DETECT, true, "Bob", AT));
        assertEquals(0, ReplaySkip.set(steps, DETECT, true, "Bob", AT));   // already so: nothing rewritten
    }

    @Test
    void onlyReplayableStepsCanBeMarked() {
        JsonObject manual = step("Manual annotation", null);
        manual.addProperty("is_scriptable", false);
        JsonObject deleted = step("Annotation", "a()");
        deleted.addProperty("deleted", true);
        JsonObject firstOpen = step("Set image type", "b()");
        firstOpen.addProperty("replay_excluded", true);
        assertFalse(ReplaySkip.isReplayable(manual));
        assertFalse(ReplaySkip.isReplayable(deleted));
        assertFalse(ReplaySkip.isReplayable(firstOpen));
        assertEquals(0, ReplaySkip.set(List.of(deleted), "a()", true, "Bob", AT));
    }

    @Test
    void exportKeepsMarkedStepsInTheSession() {
        List<JsonObject> steps = new ArrayList<>(List.of(step("Set image type", TYPE), step("Cell detection", DETECT)));
        ReplaySkip.set(steps, DETECT, true, "Bob", AT);
        List<JsonObject> exported = QTraceExporter.selectSessionSteps(steps);
        assertEquals(2, exported.size());
        assertTrue(ReplaySkip.isSkipped(exported.get(1)));
    }

    @Test
    void stampedSessionsAreNeverRewritten() {
        JsonObject root = root(session("s1", true, step("Cell detection", DETECT)),
                               session("s2", false, step("Cell detection", DETECT)),
                               session("s3", false, step("Cell detection", DETECT)));
        JsonObject stampedBefore = sessions(root).get(0).getAsJsonObject().deepCopy();
        assertEquals(2, ReplaySkip.setInUnstampedSessions(root, DETECT, true, "Bob", AT));
        assertEquals(stampedBefore, sessions(root).get(0).getAsJsonObject());
        assertTrue(ReplaySkip.isSkipped(firstStep(root, 1)));
        assertTrue(ReplaySkip.isSkipped(firstStep(root, 2)));
    }

    @Test
    void fileIsRewrittenOnlyWhenSomethingChanged(@TempDir Path dir) throws Exception {
        JsonObject root = root(session("s1", true, step("Cell detection", DETECT)),
                               session("s2", false, step("Cell detection", DETECT)));
        Path file = Files.writeString(dir.resolve("img.qtrace"), root.toString());

        assertEquals(0, ReplaySkip.setInFile(file, TYPE, true, "Bob", AT));
        assertEquals(root.toString(), Files.readString(file));

        assertEquals(1, ReplaySkip.setInFile(file, DETECT, true, "Bob", AT));
        JsonObject reread = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertFalse(ReplaySkip.isSkipped(firstStep(reread, 0)));
        assertTrue(ReplaySkip.isSkipped(firstStep(reread, 1)));
        // The chain between sessions is by id — untouched by the rewrite.
        assertEquals("s1", sessions(reread).get(1).getAsJsonObject().get("parent_session_id").getAsString());
        try (var left = Files.list(dir)) { assertEquals(1, left.count()); }   // no temp file left behind
    }

    @Test
    void aNewSessionInheritsTheLatestChoice() {
        // s1 stamped with the step replayed, s2 (later, unstamped) took it out: the choice stands.
        JsonObject s2 = session("s2", false, step("Set image type", TYPE), step("Cell detection", DETECT));
        JsonObject root = root(session("s1", true, step("Cell detection", DETECT)), s2);
        ReplaySkip.setInUnstampedSessions(root, DETECT, true, "Bob", AT);

        List<JsonObject> live = List.of(step("Set image type", TYPE), step("Cell detection", DETECT));
        assertEquals(1, ReplaySkip.inherit(live, root));
        assertFalse(ReplaySkip.isSkipped(live.get(0)));
        assertTrue(ReplaySkip.isSkipped(live.get(1)));
        assertEquals("Bob", live.get(1).get("replay_skip_by").getAsString());
    }

    @Test
    void anInstructionPutBackLaterIsNotInherited() {
        JsonObject s1 = session("s1", false, step("Cell detection", DETECT));
        ReplaySkip.set(stepsOf(s1), DETECT, true, "Bob", AT);
        JsonObject root = root(s1, session("s2", false, step("Cell detection", DETECT)));
        List<JsonObject> live = List.of(step("Cell detection", DETECT));
        assertEquals(0, ReplaySkip.inherit(live, root));
        assertFalse(ReplaySkip.isSkipped(live.get(0)));
    }

    @Test
    void summaryCountsEachInstructionOnce() {
        List<JsonObject> steps = new ArrayList<>(List.of(
            step("Set image type", TYPE), step("Cell detection", DETECT), step("Set image type", TYPE),
            step("Classify", "runObjectClassifier('c')")));
        JsonObject manual = step("Manual annotation", null);
        manual.addProperty("is_scriptable", false);
        steps.add(manual);
        ReplaySkip.set(steps, DETECT, true, "Bob", AT);
        ReplaySkip.Summary s = ReplaySkip.summary(steps);
        assertEquals(3, s.total());
        assertEquals(1, s.skipped());
        assertEquals(2, s.replayed());
    }

    @Test
    void runAgainAfterBeingTakenOutCountsAsReplayed() {
        List<JsonObject> steps = new ArrayList<>(List.of(step("Cell detection", DETECT)));
        ReplaySkip.set(steps, DETECT, true, "Bob", AT);
        steps.add(step("Cell detection", DETECT));          // the user ran it again
        assertEquals(0, ReplaySkip.summary(steps).skipped());
    }

    @Test
    void whatAStampCoversIsEverythingSinceTheLastStamp() {
        JsonObject root = root(
            session("s1", true,  step("Set image type", TYPE)),
            session("s2", false, step("Cell detection", DETECT)),
            session("s3", false, step("Classify", "runObjectClassifier('c')")));
        ReplaySkip.setInUnstampedSessions(root, DETECT, true, "Bob", AT);
        List<JsonObject> live = List.of(step("Measure", "measure()"));

        ReplaySkip.Summary s = ReplaySkip.summary(ReplaySkip.sinceLastStamp(root, live));
        assertEquals(3, s.total());          // s2 + s3 + the capture in progress — not s1, already stamped
        assertEquals(1, s.skipped());
    }

    @Test
    void withoutAnyStampEverythingRecordedCounts() {
        JsonObject root = root(session("s1", false, step("Set image type", TYPE)));
        assertEquals(2, ReplaySkip.summary(ReplaySkip.sinceLastStamp(root, List.of(step("x", DETECT)))).total());
        assertEquals(1, ReplaySkip.summary(ReplaySkip.sinceLastStamp(null, List.of(step("x", DETECT)))).total());
    }

    @Test
    void aStepReplacingASkippedOneStaysSkipped() {
        JsonObject old = step("Manual annotation: Ellipse", "ellipse(1)");
        ReplaySkip.set(List.of(old), "ellipse(1)", true, "Bob", AT);
        JsonObject moved = step("Manual annotation: Ellipse", "ellipse(2)");
        ReplaySkip.copy(old, moved);
        assertTrue(ReplaySkip.isSkipped(moved));
        assertEquals("Bob", moved.get("replay_skip_by").getAsString());

        JsonObject other = step("Manual annotation: Line", "line(2)");
        ReplaySkip.copy(step("Manual annotation: Line", "line(1)"), other);
        assertFalse(ReplaySkip.isSkipped(other));
    }

    // ── a stamp validates everything since the last one ──────────────────────

    @Test
    void stampedSessionCarriesTheUnstampedWorkBeforeIt() {
        JsonObject inherited = step("Set image type", TYPE);
        inherited.addProperty("pre_tracking", true);
        JsonObject root = root(
            session("s1", true,  step("Set image type", TYPE)),
            session("s2", false, inherited, step("Create TMA grid", "createTMAGrid()")),
            session("s3", false, step("Cell detection", DETECT)));
        ReplaySkip.setInUnstampedSessions(root, DETECT, true, "Bob", AT);
        JsonObject liveInherited = step("Set image type", TYPE);
        liveInherited.addProperty("pre_tracking", true);
        List<JsonObject> live = List.of(liveInherited, step("Measure", "measure()"));

        List<JsonObject> out = QTraceExporter.withEarlierWork(root, live);

        // What QuPath's history already held, then the work of s2 and s3, then this session's.
        assertEquals(List.of(TYPE, "createTMAGrid()", DETECT, "measure()"),
            out.stream().map(ReplaySkip::fragment).toList());
        assertEquals("s2", out.get(1).get("carried_from").getAsString());
        assertEquals("s3", out.get(2).get("carried_from").getAsString());
        assertTrue(ReplaySkip.isSkipped(out.get(2)));                    // the choice travels with the step
        assertFalse(out.get(0).has("carried_from"));
        assertFalse(out.get(3).has("carried_from"));
        assertFalse(sessions(root).get(1).getAsJsonObject().toString().contains("carried_from"));   // copies
    }

    @Test
    void workTheCaptureAlreadyHoldsIsNotCarriedTwice() {
        JsonObject root = root(session("s1", false, step("Cell detection", DETECT), step("Note", null)));
        JsonObject saved = step("Cell detection", DETECT);
        saved.addProperty("pre_tracking", true);
        JsonObject savedNote = step("Note", null);
        savedNote.addProperty("pre_tracking", true);
        List<JsonObject> out = QTraceExporter.withEarlierWork(root, List.of(saved, savedNote));
        assertEquals(2, out.size());
        assertTrue(out.stream().noneMatch(st -> st.has("carried_from")));
    }

    @Test
    void everyStampCarriesTheWholeHistoryOfTheImage() {
        // s2 was stamped carrying s1's work; the next stamp must hold all of it again, in order.
        JsonObject carried = step("Create TMA grid", "createTMAGrid()");
        carried.addProperty("carried_from", "s1");
        JsonObject root = root(
            session("s1", false, step("Create TMA grid", "createTMAGrid()")),
            session("s2", true,  carried, step("Cell detection", DETECT)),
            session("s3", false, step("Classify", "runObjectClassifier('c')")));

        List<JsonObject> out = QTraceExporter.withEarlierWork(root, List.of(step("Measure", "measure()")));

        assertEquals(List.of("createTMAGrid()", DETECT, "runObjectClassifier('c')", "measure()"),
            out.stream().map(ReplaySkip::fragment).toList());
        assertEquals(List.of("s1", "s2", "s3"),
            out.subList(0, 3).stream().map(st -> st.get("carried_from").getAsString()).toList());
        assertEquals(1, QTraceExporter.withEarlierWork(null, List.of(step("Measure", "measure()"))).size());
    }

    @Test
    void unstampedSessionsBeforeAStampAreFrozenWithIt() {
        JsonObject root = root(
            session("s1", false, step("Cell detection", DETECT)),
            session("s2", true,  step("Cell detection", DETECT)),
            session("s3", false, step("Cell detection", DETECT)));
        assertEquals(1, ReplaySkip.setInUnstampedSessions(root, DETECT, true, "Bob", AT));
        assertFalse(ReplaySkip.isSkipped(firstStep(root, 0)));
        assertTrue(ReplaySkip.isSkipped(firstStep(root, 2)));
        assertEquals(1, ReplaySkip.lastStampedIndex(root));
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static JsonObject step(String command, String script) {
        JsonObject st = new JsonObject();
        st.addProperty("command", command);
        st.addProperty("is_scriptable", script != null);
        if (script != null) st.addProperty("script_fragment", script);
        return st;
    }

    private static JsonObject session(String id, boolean stamped, JsonObject... steps) {
        JsonObject s = new JsonObject();
        s.addProperty("session_id", id);
        JsonArray arr = new JsonArray();
        for (JsonObject st : steps) arr.add(st);
        s.add("steps", arr);
        if (stamped) s.add("validation", JsonParser.parseString("{\"validator\":\"Jane Doe\"}"));
        else         s.add("validation", JsonNull.INSTANCE);
        s.addProperty("validation_state", stamped ? "stamped" : "unstamped");
        return s;
    }

    private static JsonObject root(JsonObject... sessions) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        String parent = null;
        for (JsonObject s : sessions) {
            if (parent != null) s.addProperty("parent_session_id", parent);
            parent = s.get("session_id").getAsString();
            arr.add(s);
        }
        root.add("sessions", arr);
        return root;
    }

    private static JsonArray sessions(JsonObject root) { return root.getAsJsonArray("sessions"); }

    private static JsonObject firstStep(JsonObject root, int session) {
        return sessions(root).get(session).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject();
    }

    private static List<JsonObject> stepsOf(JsonObject session) {
        List<JsonObject> out = new ArrayList<>();
        session.getAsJsonArray("steps").forEach(e -> out.add(e.getAsJsonObject()));
        return out;
    }
}
