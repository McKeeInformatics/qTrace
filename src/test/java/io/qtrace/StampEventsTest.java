package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StampEventsTest {

    @AfterEach
    void clear() {
        StampEvents.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void aModuleIsToldWhichImageWasJustStamped() {
        ModuleEntitlements.setForTest(Set.of("cohort"));
        List<String> seen = new ArrayList<>();
        StampEvents.register("cohort", seen::add);
        StampEvents.stamped("TMA_A-3.ome.tiff");
        assertEquals(List.of("TMA_A-3.ome.tiff"), seen);
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeIsNotTold() {
        ModuleEntitlements.setForTest(Set.of("compliance"));
        List<String> seen = new ArrayList<>();
        StampEvents.register("cohort", seen::add);
        StampEvents.stamped("a.tiff");
        assertTrue(seen.isEmpty());
    }

    @Test
    void aListenerThatFailsNeitherBreaksTheStampNorTheOthers() {
        ModuleEntitlements.setForTest(Set.of("cohort", "other"));
        List<String> seen = new ArrayList<>();
        StampEvents.register("cohort", name -> { throw new IllegalStateException("boom"); });
        StampEvents.register("other", seen::add);
        assertDoesNotThrow(() -> StampEvents.stamped("a.tiff"));
        assertEquals(List.of("a.tiff"), seen);
    }

    @Test
    void aModuleRegisteringTwiceIsToldOnce() {
        ModuleEntitlements.setForTest(Set.of("cohort"));
        List<String> seen = new ArrayList<>();
        StampEvents.register("cohort", seen::add);
        StampEvents.register("cohort", seen::add);
        StampEvents.stamped("a.tiff");
        assertEquals(1, seen.size());
    }

    @Test
    void nothingHappensWithoutAnImageNameOrAListener() {
        ModuleEntitlements.setForTest(Set.of("cohort"));
        List<String> seen = new ArrayList<>();
        assertDoesNotThrow(() -> StampEvents.stamped("a.tiff"));
        StampEvents.register("cohort", seen::add);
        StampEvents.stamped(null);
        assertTrue(seen.isEmpty());
    }
}
