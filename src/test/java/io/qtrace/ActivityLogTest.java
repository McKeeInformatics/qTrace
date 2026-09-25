package io.qtrace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActivityLogTest {

    // 14:05:09 local time, whatever the machine's zone.
    private static final java.time.ZoneId ZONE = java.time.ZoneId.of("Europe/Paris");
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
        java.time.ZonedDateTime.of(2026, 9, 24, 14, 5, 9, 0, ZONE).toInstant(), ZONE);

    @BeforeEach
    void clear() {
        ActivityLog.resetForTests();
        ActivityLog.setClockForTests(CLOCK);
    }

    @Test
    void everyLineIsTimestampedWhenItIsLogged() {
        ActivityLog.add("Step 1 captured: Cell detection");
        assertEquals(List.of("14:05:09  Step 1 captured: Cell detection"), ActivityLog.lines());
    }

    @Test
    void linesLoggedWithoutAPanelAreKept() {
        ActivityLog.add("Step 1 captured: Cell detection");
        ActivityLog.add("Annotation updated: Rectangle");
        assertEquals(List.of("14:05:09  Step 1 captured: Cell detection", "14:05:09  Annotation updated: Rectangle"),
                     ActivityLog.lines());
    }

    @Test
    void attachingASinkReplaysHistoryThenFollowsLive() {
        ActivityLog.add("before");
        List<String> seen = new ArrayList<>();
        List<String> history = ActivityLog.attach(seen::add);
        assertEquals(List.of("14:05:09  before"), history);
        ActivityLog.add("after");
        assertEquals(List.of("14:05:09  after"), seen);
    }

    @Test
    void aNewSinkReplacesThePreviousOne() {
        List<String> first = new ArrayList<>(), second = new ArrayList<>();
        ActivityLog.attach(first::add);
        ActivityLog.attach(second::add);
        ActivityLog.add("x");
        assertTrue(first.isEmpty());
        assertEquals(List.of("14:05:09  x"), second);
    }

    @Test
    void historyIsBounded() {
        for (int i = 0; i < ActivityLog.MAX_LINES + 10; i++) ActivityLog.add("line " + i);
        List<String> lines = ActivityLog.lines();
        assertEquals(ActivityLog.MAX_LINES, lines.size());
        assertEquals("14:05:09  line 10", lines.get(0));
    }

    @Test
    void multiLineMessagesAndNullsAreSafe() {
        ActivityLog.add(null);
        ActivityLog.add("a\nb");
        // Continuation lines are indented under the text, not re-stamped.
        assertEquals(List.of("14:05:09  a\n          b"), ActivityLog.lines());
    }
}
