package io.qtrace.draft;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutosaveSchedulerTest {

    private int flushes;
    private boolean forcedSeen;
    private long snapshotMillis;
    private RuntimeException failure;
    private final List<AutosaveScheduler.Status> statuses = new ArrayList<>();
    private AutosaveScheduler s;
    private Runnable duringFlush = () -> {};

    @BeforeEach
    void setUp() {
        flushes = 0; snapshotMillis = 0; failure = null; forcedSeen = false;
        s = new AutosaveScheduler(changedOnly -> {
            duringFlush.run();
            if (failure != null) throw failure;
            flushes++;
            if (!changedOnly) forcedSeen = true;
            return new AutosaveScheduler.FlushResult(true, snapshotMillis);
        }, e -> statuses.add(e.status()), 2_000, 30_000);
    }

    @Test
    void burstOfChangesIsCoalescedIntoOneWriteAfterTheQuietPeriod() {
        s.markDirty(0);
        s.markDirty(500);
        s.markDirty(1_500);
        assertFalse(s.poll(3_000), "only 1.5 s since the last change");
        assertTrue(s.poll(3_500));
        assertEquals(1, flushes);
        assertFalse(s.poll(4_000), "nothing new to write");
        assertEquals(List.of(AutosaveScheduler.Status.SAVING, AutosaveScheduler.Status.SAVED), statuses);
    }

    @Test
    void safetyNetRunsEvenWithoutNotification() {
        assertFalse(s.poll(29_000));
        assertTrue(s.poll(30_000));
        assertEquals(1, flushes);
        assertFalse(s.poll(31_000));
        assertTrue(s.poll(60_000));
    }

    @Test
    void safetyNetAsksTheFlusherToWriteOnlyIfSomethingChanged() {
        s.poll(30_000);
        assertFalse(forcedSeen);
        s.markDirty(40_000);
        s.poll(42_000);
        assertTrue(forcedSeen, "a notified change is written unconditionally");
    }

    @Test
    void slowSnapshotsStretchTheQuietPeriod() {
        snapshotMillis = 1_000;         // next debounce: max(2 s, 5 × 1 s) = 5 s
        s.markDirty(0);
        s.poll(2_000);
        s.markDirty(10_000);
        assertFalse(s.poll(12_000));
        assertTrue(s.poll(15_000));
        assertEquals(2, flushes);
    }

    @Test
    void quietPeriodIsCapped() {
        snapshotMillis = 1_000_000;
        s.markDirty(0);
        s.poll(2_000);
        s.markDirty(10_000);
        assertTrue(s.poll(10_000 + AutosaveScheduler.MAX_DEBOUNCE_MS));
    }

    @Test
    void failureIsReportedAndRetried() {
        failure = new RuntimeException("disk full");
        s.markDirty(0);
        assertTrue(s.poll(2_000));
        assertEquals(AutosaveScheduler.Status.FAILED, statuses.get(statuses.size() - 1));
        assertEquals("disk full", s.lastEvent().message());
        failure = null;
        assertTrue(s.poll(4_000), "still dirty — retried after another quiet period");
        assertEquals(AutosaveScheduler.Status.SAVED, statuses.get(statuses.size() - 1));
    }

    @Test
    void changeDuringAWriteIsNotLost() {
        s.markDirty(0);
        duringFlush = () -> { duringFlush = () -> {}; s.markDirty(2_100); };
        s.poll(2_000);
        assertTrue(s.poll(4_100));
        assertEquals(2, flushes);
    }

    @Test
    void flushNowWritesImmediately() {
        s.markDirty(0);
        s.flushNowSync(100);
        assertEquals(1, flushes);
        assertFalse(s.poll(2_500));
    }
}
