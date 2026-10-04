package io.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Version window in editing mode lists a workflow in the order it plays. */
class VersionTimelineWorkflowOrderTest {

    private static JsonObject step(String command, String script) {
        JsonObject s = new JsonObject();
        s.addProperty("command", command);
        s.addProperty("timestamp", "2026-10-04T12:00:00Z");
        s.addProperty("is_scriptable", true);
        s.addProperty("script_fragment", script);
        return s;
    }

    private static JsonObject packet(JsonObject... steps) {
        JsonObject p = new JsonObject();
        p.addProperty("exported_at", "2026-10-04T12:00:00Z");
        p.add("validation", JsonNull.INSTANCE);
        JsonArray arr = new JsonArray();
        for (JsonObject s : steps) arr.add(s);
        p.add("steps", arr);
        return p;
    }

    private static List<VersionTimeline.Entry> entries(JsonObject... packets) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (JsonObject p : packets) arr.add(p);
        root.add("sessions", arr);
        return VersionTimeline.build(root, ZoneOffset.UTC);
    }

    private static List<String> commands(List<VersionTimeline.Entry> rows) {
        return rows.stream().map(e -> e.kind() == VersionTimeline.Kind.STEP ? e.command() : "#" + e.sessionIndex()).toList();
    }

    @Test
    void packetsAndInstructionsComeInPlayOrder() {
        List<VersionTimeline.Entry> all = entries(packet(step("A", "a()"), step("B", "b()")), packet(step("C", "c()")));
        assertEquals(List.of("#0", "A", "B", "#1", "C"), commands(VersionTimeline.workflowOrder(all, null)));
        assertEquals(List.of("#1", "C"), commands(VersionTimeline.workflowOrder(all, 1)));
    }

    @Test
    void theSameInstructionTwiceIsListedTwice() {
        List<VersionTimeline.Entry> all = entries(packet(step("A", "a()"), step("A", "a()")));
        assertEquals(List.of("#0", "A", "A"), commands(VersionTimeline.workflowOrder(all, null)));
    }

    @Test
    void recordsCapturedOnTheSideAreNotInstructions() {
        JsonObject p = packet(step("A", "a()"));
        JsonObject clf = new JsonObject();
        clf.addProperty("name", "tissue");
        clf.addProperty("saved_at", "2026-10-04T11:00:00Z");
        JsonArray arr = new JsonArray();
        arr.add(clf);
        p.add("pixel_classifiers", arr);
        assertEquals(List.of("#0", "A"), commands(VersionTimeline.workflowOrder(entries(p), null)));
    }

    @Test
    void aRowKnowsItsPlaceInItsPacket() {
        List<VersionTimeline.Entry> all = entries(packet(step("A", "a()"), step("A", "a()")), packet(step("C", "c()")));
        List<VersionTimeline.Entry> rows = VersionTimeline.workflowOrder(all, null);
        assertEquals(-1, VersionTimeline.stepIndex(all, rows.get(0)), "a packet row is not an instruction");
        assertEquals(0, VersionTimeline.stepIndex(all, rows.get(1)));
        assertEquals(1, VersionTimeline.stepIndex(all, rows.get(2)), "equal rows are told apart");
        assertEquals(0, VersionTimeline.stepIndex(all, rows.get(4)));
    }
}
