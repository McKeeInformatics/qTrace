package io.qtrace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The single update message: what it lists, and what "skip" remembers. */
class UpdateBatchTest {

    private static UpdateBatch.Item item(String module, String from, String to) {
        return new UpdateBatch.Item(module, from, to, null, () -> new byte[0]);
    }

    @Test
    void oneLinePerExtensionCoreAndComplianceFirst() {
        UpdateBatch b = UpdateBatch.of(List.of(item("training", "0.1.0", "0.2.0"), item("compliance", "1.2.4", "1.2.5"), item("core", "1.2.4", "1.2.5")));
        assertEquals(List.of("Core  1.2.4 → 1.2.5", "Compliance  1.2.4 → 1.2.5", "Training  0.1.0 → 0.2.0"), b.lines());
    }

    @Test
    void anExtensionNotInstalledYetIsNew() {
        UpdateBatch b = UpdateBatch.of(List.of(item("training", "0", "0.1.0")));
        assertEquals(List.of("Training  0.1.0 (new)"), b.lines());
    }

    @Test
    void theSameExtensionOfferedTwiceIsListedOnceAtItsHighestVersion() {
        UpdateBatch b = UpdateBatch.of(List.of(item("core", "1.2.4", "1.2.5"), item("core", "1.2.4", "1.2.6")));
        assertEquals(List.of("Core  1.2.4 → 1.2.6"), b.lines());
        assertEquals(1, b.items().size());
    }

    @Test
    void whatWasInstalledIsNamedWithItsVersion() {
        assertEquals("Core v1.2.5", UpdateBatch.label(item("core", "1.2.4", "1.2.5")));
        assertEquals("Security v0.2.0", UpdateBatch.label(item("security", "0.1.0", "0.2.0")));
    }

    @Test
    void skipRemembersExactlyThisSetOfVersions() {
        UpdateBatch b = UpdateBatch.of(List.of(item("training", "0.1.0", "0.2.0"), item("core", "1.2.4", "1.2.5")));
        assertEquals("core@1.2.5,training@0.2.0", b.signature());
        assertTrue(b.wasSkipped("core@1.2.5,training@0.2.0"));
        assertFalse(b.wasSkipped("core@1.2.5"));              // one more extension since: ask again
        assertFalse(b.wasSkipped("core@1.2.5,training@0.3.0")); // a newer version since: ask again
        assertFalse(b.wasSkipped(null));
    }

    @Test
    void aVersionSkippedBeforeTheSingleMessageStillCountsForThatOneExtension() {
        // What qTrace stored until now: the bare version of the one module it had asked about.
        assertTrue(UpdateBatch.of(List.of(item("core", "1.2.4", "1.2.5"))).wasSkipped("1.2.5"));
        assertFalse(UpdateBatch.of(List.of(item("core", "1.2.4", "1.2.5"), item("training", "0.1.0", "1.2.5"))).wasSkipped("1.2.5"));
    }

    @Test
    void nothingToOfferIsEmpty() {
        assertTrue(UpdateBatch.of(List.of()).isEmpty());
    }
}
