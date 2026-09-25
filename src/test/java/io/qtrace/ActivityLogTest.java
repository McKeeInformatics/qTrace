package io.qtrace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActivityLogTest {

    @BeforeEach
    void clear() {
        ActivityLog.resetForTests();
    }

    @Test
    void linesLoggedWithoutAPanelAreKept() {
        ActivityLog.add("Step 1 captured: Cell detection");
        ActivityLog.add("Annotation updated: Rectangle");
        assertEquals(List.of("Step 1 captured: Cell detection", "Annotation updated: Rectangle"),
                     ActivityLog.lines());
    }

    @Test
    void attachingASinkReplaysHistoryThenFollowsLive() {
        ActivityLog.add("before");
        List<String> seen = new ArrayList<>();
        List<String> history = ActivityLog.attach(seen::add);
        assertEquals(List.of("before"), history);
        ActivityLog.add("after");
        assertEquals(List.of("after"), seen);
    }

    @Test
    void aNewSinkReplacesThePreviousOne() {
        List<String> first = new ArrayList<>(), second = new ArrayList<>();
        ActivityLog.attach(first::add);
        ActivityLog.attach(second::add);
        ActivityLog.add("x");
        assertTrue(first.isEmpty());
        assertEquals(List.of("x"), second);
    }

    @Test
    void historyIsBounded() {
        for (int i = 0; i < ActivityLog.MAX_LINES + 10; i++) ActivityLog.add("line " + i);
        List<String> lines = ActivityLog.lines();
        assertEquals(ActivityLog.MAX_LINES, lines.size());
        assertEquals("line 10", lines.get(0));
    }

    @Test
    void multiLineMessagesAndNullsAreSafe() {
        ActivityLog.add(null);
        ActivityLog.add("a\nb");
        assertEquals(List.of("a\nb"), ActivityLog.lines());
    }
}
