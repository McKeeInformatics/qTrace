package io.qtrace;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VersionEditorsTest {

    private static VersionEditor editor(String label) {
        return new VersionEditor() {
            @Override public String label() { return label; }
            @Override public Document open(JsonObject traceRoot, File trace) { return null; }
        };
    }

    @AfterEach
    void clear() {
        VersionEditors.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void noEditorWithoutAModule() {
        ModuleEntitlements.setForTest(Set.of("workfloweditor"));
        assertTrue(VersionEditors.entitled().isEmpty());
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeOffersNothing() {
        VersionEditors.register("workfloweditor", editor("Super User"));
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertTrue(VersionEditors.entitled().isEmpty());
        ModuleEntitlements.setForTest(Set.of("compliance", "workfloweditor"));
        assertEquals(1, VersionEditors.entitled().size());
    }

    @Test
    void editorsComeInRegistrationOrder() {
        ModuleEntitlements.setForTest(Set.of("workfloweditor", "other"));
        VersionEditor a = editor("A"), b = editor("B");
        VersionEditors.register("workfloweditor", a);
        VersionEditors.register("other", b);
        assertEquals(List.of(a, b), VersionEditors.entitled());
    }

    @Test
    void aModuleRegisteringTwiceIsListedOnce() {
        ModuleEntitlements.setForTest(Set.of("workfloweditor"));
        VersionEditor first = editor("A"), again = editor("A");
        VersionEditors.register("workfloweditor", first);
        VersionEditors.register("workfloweditor", again);
        assertEquals(List.of(again), VersionEditors.entitled());
    }

    @Test
    void nullsAreIgnored() {
        ModuleEntitlements.setForTest(Set.of("workfloweditor"));
        VersionEditors.register(null, editor("A"));
        VersionEditors.register("workfloweditor", null);
        assertTrue(VersionEditors.entitled().isEmpty());
    }
}
