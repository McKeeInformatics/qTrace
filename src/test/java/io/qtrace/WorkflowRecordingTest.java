package io.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** "Record" in the Version window's editing mode: what the user did in QuPath since pressing it. */
class WorkflowRecordingTest {

    private static JsonObject step(String command, String script, String timestamp) {
        JsonObject s = new JsonObject();
        s.addProperty("command", command);
        s.addProperty("timestamp", timestamp);
        s.addProperty("is_scriptable", script != null);
        if (script != null) s.addProperty("script_fragment", script);
        return s;
    }

    private static JsonObject flagged(JsonObject step, String flag) {
        step.addProperty(flag, true);
        return step;
    }

    private static JsonObject live(JsonObject... steps) {
        JsonObject s = new JsonObject();
        JsonArray arr = new JsonArray();
        for (JsonObject st : steps) arr.add(st);
        s.add("steps", arr);
        return s;
    }

    private static List<String> scripts(List<JsonObject> steps) {
        return steps.stream().map(s -> s.get("script_fragment").getAsString()).toList();
    }

    @Test
    void onlyWhatIsDoneAfterRecordCounts() {
        WorkflowRecording rec = new WorkflowRecording(live(step("A", "a()", "t1")));
        assertTrue(rec.recorded(live(step("A", "a()", "t1"))).isEmpty());
        assertEquals(List.of("b()", "c()"), scripts(rec.recorded(
            live(step("A", "a()", "t1"), step("B", "b()", "t2"), step("C", "c()", "t3")))));
    }

    @Test
    void recordingStartsOnAnImageWithNoCaptureYet() {
        WorkflowRecording rec = new WorkflowRecording(null);
        assertTrue(rec.recorded(null).isEmpty());
        assertEquals(List.of("a()"), scripts(rec.recorded(live(step("A", "a()", "t1")))));
    }

    @Test
    void theSameCommandRunAgainIsANewInstruction() {
        WorkflowRecording rec = new WorkflowRecording(live(step("A", "a()", "t1")));
        assertEquals(List.of("a()"), scripts(rec.recorded(live(step("A", "a()", "t1"), step("A", "a()", "t2")))));
    }

    @Test
    void anInstructionReshapedAfterwardsIsRecordedAsItIsNow() {
        // A drawn annotation is one step whose script follows the shape while it is dragged.
        WorkflowRecording rec = new WorkflowRecording(null);
        assertEquals(List.of("rect(1)"), scripts(rec.recorded(live(step("Rectangle", "rect(1)", "t1")))));
        assertEquals(List.of("rect(2)"), scripts(rec.recorded(live(step("Rectangle", "rect(2)", "t1")))));
    }

    @Test
    void whatTheReplayWouldNotRunIsNotRecorded() {
        JsonObject skipped = step("Skipped", "s()", "t5");
        skipped.addProperty("replay_skip", true);
        WorkflowRecording rec = new WorkflowRecording(null);
        assertEquals(List.of("k()"), scripts(rec.recorded(live(
            step("Brush", null, "t1"),
            flagged(step("Deleted", "d()", "t2"), "deleted"),
            flagged(step("Excluded", "e()", "t3"), "replay_excluded"),
            skipped,
            step("Kept", "k()", "t6")))));
    }

    @Test
    void theHistoryOfAnImageOpenedMeanwhileIsNotRecorded() {
        // Opening another image loads its QuPath history: read, not done now.
        WorkflowRecording rec = new WorkflowRecording(live(step("A", "a()", "t1")));
        assertEquals(List.of("n()"), scripts(rec.recorded(live(
            flagged(step("Old", "old()", "t0"), "pre_tracking"),
            step("New", "n()", "t9")))));
    }
}
