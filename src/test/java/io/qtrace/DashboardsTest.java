package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DashboardsTest {

    /** A module's Dashboard that opens nothing. */
    private static final Dashboard NONE = () -> null;

    @AfterEach
    void clear() {
        Dashboards.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void noDashboardWithoutAModule() {
        ModuleEntitlements.setForTest(Set.of("dashboard"));
        assertNull(Dashboards.entitled());
    }

    @Test
    void theRegisteredModuleBringsTheDashboard() {
        ModuleEntitlements.setForTest(Set.of("dashboard"));
        Dashboards.register("dashboard", NONE);
        assertSame(NONE, Dashboards.entitled());
    }

    @Test
    void aModuleNoLongerServedBringsNothing() {
        Dashboards.register("dashboard", NONE);
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertNull(Dashboards.entitled());
    }

    @Test
    void aModuleRegisteringTwiceKeepsItsLastFactory() {
        ModuleEntitlements.setForTest(Set.of("dashboard"));
        Dashboard again = () -> null;
        Dashboards.register("dashboard", NONE);
        Dashboards.register("dashboard", again);
        assertSame(again, Dashboards.entitled());
    }

    @Test
    void nullsAreIgnored() {
        ModuleEntitlements.setForTest(Set.of("dashboard"));
        Dashboards.register(null, NONE);
        Dashboards.register("dashboard", null);
        assertNull(Dashboards.entitled());
    }

    @Test
    void listenersAreToldWhenAModuleRegisters() {
        int[] told = {0};
        Dashboards.onChange(this, () -> told[0]++);
        Dashboards.register("dashboard", NONE);
        assertEquals(1, told[0]);
    }

    @Test
    void coreNoLongerCarriesTheDashboardItself() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.qtrace.QTraceDashboard"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.qtrace.ExportFieldsDialog"));
    }
}
