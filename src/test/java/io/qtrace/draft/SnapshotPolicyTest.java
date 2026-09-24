package io.qtrace.draft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotPolicyTest {

    private static final long MB = 1024 * 1024;
    private final SnapshotPolicy auto = SnapshotPolicy.auto();

    @Test
    void autoDefaults() {
        assertEquals(SnapshotPolicy.Mode.AUTO, auto.mode());
        assertEquals(50 * MB, auto.largeThresholdBytes());
        assertEquals(60_000, auto.largeIntervalMs());
        assertFalse(auto.largeKeyStepsOnly());
    }

    @Test
    void nothingPendingNoSnapshot() {
        assertFalse(auto.shouldSnapshot(1 * MB, 0, 100_000, false, false));
    }

    @Test
    void smallImageIsCopiedAfterEveryChange() {
        assertTrue(auto.shouldSnapshot(10 * MB, 99_000, 100_000, false, true));
    }

    @Test
    void unknownSizeIsCopiedToLearnIt() {
        assertTrue(auto.shouldSnapshot(0, 99_000, 100_000, false, true));
    }

    @Test
    void largeImageIsCopiedRightAfterAKeyStep() {
        assertTrue(auto.shouldSnapshot(200 * MB, 99_000, 100_000, true, false));
    }

    @Test
    void largeImageMinorEditsWaitForTheInterval() {
        assertFalse(auto.shouldSnapshot(200 * MB, 50_000, 100_000, false, true));
        assertTrue(auto.shouldSnapshot(200 * MB, 40_000, 100_000, false, true));
    }

    @Test
    void keyStepsOnlyNeverCopiesForMinorEdits() {
        SnapshotPolicy p = SnapshotPolicy.custom(100, 30, true);
        assertFalse(p.shouldSnapshot(200 * MB, 0, 1_000_000, false, true));
        assertTrue(p.shouldSnapshot(200 * MB, 0, 1_000_000, true, true));
        assertTrue(p.shouldSnapshot(50 * MB, 0, 1_000_000, false, true), "under the custom threshold");
    }

    @Test
    void customValuesAreClamped() {
        SnapshotPolicy p = SnapshotPolicy.custom(0, 1, false);
        assertEquals(SnapshotPolicy.MIN_THRESHOLD_MB * MB, p.largeThresholdBytes());
        assertEquals(SnapshotPolicy.MIN_INTERVAL_S * 1000L, p.largeIntervalMs());
    }

    @Test
    void keyWorkflowSteps() {
        assertTrue(SnapshotPolicy.isKeyWorkflowStep("Cell detection"));
        assertTrue(SnapshotPolicy.isKeyWorkflowStep("Run script"));
        assertFalse(SnapshotPolicy.isKeyWorkflowStep("Manual annotation: Rectangle"));
        assertFalse(SnapshotPolicy.isKeyWorkflowStep(null));
    }

    @Test
    void batchHierarchyChangesAreKeySteps() {
        assertTrue(SnapshotPolicy.isKeyHierarchyChange(SnapshotPolicy.BATCH_OBJECTS));
        assertFalse(SnapshotPolicy.isKeyHierarchyChange(1));
    }
}
