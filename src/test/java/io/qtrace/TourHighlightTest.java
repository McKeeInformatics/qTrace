package io.qtrace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TourHighlightTest {

    @Test
    void noToolShownLeavesEveryButtonPlain() {
        assertEquals(TourHighlight.Look.PLAIN, TourHighlight.look("", "stamp"));
        assertEquals(TourHighlight.Look.PLAIN, TourHighlight.look(null, "stamp"));
    }

    @Test
    void theShownToolIsOutlinedAndTheOthersAreGreyed() {
        assertEquals(TourHighlight.Look.ON, TourHighlight.look("stamp", "stamp"));
        assertEquals(TourHighlight.Look.OFF, TourHighlight.look("stamp", "upload"));
    }

    @Test
    void aToolsOwnWelcomeOutlinesItsButtonWithoutGreyingTheOthers() {
        assertEquals(TourHighlight.Look.ON, TourHighlight.look("player", "player", false));
        assertEquals(TourHighlight.Look.PLAIN, TourHighlight.look("player", "upload", false));
    }
}
