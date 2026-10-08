package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VersionGraphsTest {

    /** A module's Version window factory that opens nothing. */
    private static final VersionGraph NONE = host -> null;

    @AfterEach
    void clear() {
        VersionGraphs.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void noVersionWindowWithoutAModule() {
        ModuleEntitlements.setForTest(Set.of("versiongraph"));
        assertNull(VersionGraphs.entitled());
    }

    @Test
    void theRegisteredModuleBringsTheVersionWindow() {
        ModuleEntitlements.setForTest(Set.of("versiongraph"));
        VersionGraphs.register("versiongraph", NONE);
        assertSame(NONE, VersionGraphs.entitled());
    }

    @Test
    void aModuleNoLongerServedBringsNothing() {
        VersionGraphs.register("versiongraph", NONE);
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertNull(VersionGraphs.entitled());
    }

    @Test
    void aModuleRegisteringTwiceKeepsItsLastFactory() {
        ModuleEntitlements.setForTest(Set.of("versiongraph"));
        VersionGraph again = host -> null;
        VersionGraphs.register("versiongraph", NONE);
        VersionGraphs.register("versiongraph", again);
        assertSame(again, VersionGraphs.entitled());
    }

    @Test
    void nullsAreIgnored() {
        ModuleEntitlements.setForTest(Set.of("versiongraph"));
        VersionGraphs.register(null, NONE);
        VersionGraphs.register("versiongraph", null);
        assertNull(VersionGraphs.entitled());
    }

    @Test
    void listenersAreToldWhenAModuleRegisters() {
        int[] told = {0};
        VersionGraphs.onChange(this, () -> told[0]++);
        VersionGraphs.register("versiongraph", NONE);
        assertEquals(1, told[0]);
    }
}
