package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FileActionsTest {

    @AfterEach
    void clear() {
        FileActions.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void noActionWithoutAModule() {
        ModuleEntitlements.setForTest(Set.of("library"));
        assertTrue(FileActions.entitled("qtflow").isEmpty());
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeOffersNothing() {
        FileActions.register("library", "qtflow", "Open", f -> {});
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertTrue(FileActions.entitled("qtflow").isEmpty());
        ModuleEntitlements.setForTest(Set.of("compliance", "library"));
        assertEquals(1, FileActions.entitled("qtflow").size());
    }

    @Test
    void anActionIsOfferedForItsOwnKindOfFileOnly() {
        ModuleEntitlements.setForTest(Set.of("library"));
        FileActions.register("library", "qtflow", "Open", f -> {});
        assertTrue(FileActions.entitled("qtrace").isEmpty());
        assertEquals("Open", FileActions.entitled("qtflow").get(0).label());
    }

    @Test
    void theExtensionIsReadWithOrWithoutItsDotAndWhateverItsCase() {
        ModuleEntitlements.setForTest(Set.of("library"));
        FileActions.register("library", ".QTFLOW", "Open", f -> {});
        assertEquals(1, FileActions.entitled("qtflow").size());
        assertEquals(1, FileActions.entitled(".qtflow").size());
    }

    @Test
    void anActionRunsOnTheFileItIsGiven() {
        ModuleEntitlements.setForTest(Set.of("library"));
        List<File> opened = new ArrayList<>();
        FileActions.register("library", "qtflow", "Open", opened::add);
        File f = new File("a.qtflow");
        FileActions.entitled("qtflow").get(0).run(f);
        assertEquals(List.of(f), opened);
    }

    @Test
    void actionsComeInRegistrationOrderAndAModuleRegisteringAgainIsListedOnce() {
        ModuleEntitlements.setForTest(Set.of("a", "b"));
        FileActions.register("a", "qtflow", "First", f -> {});
        FileActions.register("b", "qtflow", "Second", f -> {});
        FileActions.register("a", "qtflow", "First again", f -> {});
        List<String> labels = FileActions.entitled("qtflow").stream().map(FileActions.Action::label).toList();
        assertEquals(List.of("Second", "First again"), labels);
    }

    @Test
    void nullsAreIgnored() {
        ModuleEntitlements.setForTest(Set.of("library"));
        FileActions.register(null, "qtflow", "Open", f -> {});
        FileActions.register("library", null, "Open", f -> {});
        FileActions.register("library", "qtflow", null, f -> {});
        FileActions.register("library", "qtflow", "Open", null);
        assertTrue(FileActions.entitled("qtflow").isEmpty());
    }
}
