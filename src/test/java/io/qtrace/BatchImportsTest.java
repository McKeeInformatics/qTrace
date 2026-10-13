package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BatchImportsTest {

    /** A module's Import that does nothing. */
    private static final BatchImport NONE = controller -> {};

    @AfterEach
    void clear() {
        BatchImports.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void noImportWithoutAModule() {
        ModuleEntitlements.setForTest(Set.of("import"));
        assertNull(BatchImports.entitled());
    }

    @Test
    void theRegisteredModuleBringsImport() {
        ModuleEntitlements.setForTest(Set.of("import"));
        BatchImports.register("import", NONE);
        assertSame(NONE, BatchImports.entitled());
    }

    @Test
    void aModuleNotServedBringsNothing() {
        BatchImports.register("import", NONE);
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertNull(BatchImports.entitled());
    }

    @Test
    void aModuleRegisteringTwiceKeepsItsLastImport() {
        ModuleEntitlements.setForTest(Set.of("import"));
        BatchImport again = controller -> {};
        BatchImports.register("import", NONE);
        BatchImports.register("import", again);
        assertSame(again, BatchImports.entitled());
    }

    @Test
    void nullsAreIgnored() {
        ModuleEntitlements.setForTest(Set.of("import"));
        BatchImports.register(null, NONE);
        BatchImports.register("import", null);
        assertNull(BatchImports.entitled());
    }

    @Test
    void listenersAreToldWhenAModuleRegisters() {
        int[] told = {0};
        BatchImports.onChange(this, () -> told[0]++);
        BatchImports.register("import", NONE);
        assertEquals(1, told[0]);
    }

    @Test
    void coreNoLongerCarriesTheBatchItself() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.qtrace.QTraceBatchExporter"));
    }
}
