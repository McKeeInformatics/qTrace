package io.qtrace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where the mini-panel column sits: a fraction of the free space, kept inside the viewers. */
class MiniPanelPlacementTest {

    private static final double MARGIN = 8;

    @Test
    void fractionOneIsFlushWithTheFarEdgeMinusTheMargin() {
        // 1000 wide viewers, 40 wide column: free = 1000 - 40 - 16 = 944
        assertEquals(8 + 944, MiniPanelPlacement.position(1.0, 1000, 40, MARGIN));
        assertEquals(8, MiniPanelPlacement.position(0.0, 1000, 40, MARGIN));
        assertEquals(8 + 472, MiniPanelPlacement.position(0.5, 1000, 40, MARGIN));
    }

    @Test
    void outOfRangeFractionsAreClamped() {
        assertEquals(8, MiniPanelPlacement.position(-3, 1000, 40, MARGIN));
        assertEquals(952, MiniPanelPlacement.position(7, 1000, 40, MARGIN));
    }

    @Test
    void columnLargerThanTheViewersStaysAtTheMargin() {
        assertEquals(8, MiniPanelPlacement.position(1.0, 30, 40, MARGIN));
    }

    @Test
    void draggedPositionBecomesAFraction() {
        assertEquals(0.5, MiniPanelPlacement.fraction(480, 1000, 40, MARGIN, 0.2));
        assertEquals(0.0, MiniPanelPlacement.fraction(-50, 1000, 40, MARGIN, 0.2));
        assertEquals(1.0, MiniPanelPlacement.fraction(5000, 1000, 40, MARGIN, 0.2));
    }

    @Test
    void noFreeSpaceKeepsTheCurrentFraction() {
        assertEquals(0.2, MiniPanelPlacement.fraction(10, 30, 40, MARGIN, 0.2));
    }
}
