package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelExtensionsTest {

    private static final Runnable NOTHING = () -> {};

    @AfterEach
    void clear() {
        PanelExtensions.clear();
        ModuleEntitlements.setForTest(null);
    }

    private static List<String> labels(String anchor) {
        return PanelExtensions.entitled(anchor).stream().map(PanelExtensions.Entry::label).toList();
    }

    @Test
    void aButtonWithoutAModuleIsFollowedByNothing() {
        ModuleEntitlements.setForTest(Set.of("cohort"));
        assertTrue(PanelExtensions.entitled(PanelExtensions.DASHBOARD).isEmpty());
    }

    @Test
    void aModuleButtonFollowsItsOwnButtonOnly() {
        ModuleEntitlements.setForTest(Set.of("cohort", "workfloweditor"));
        PanelExtensions.register("cohort", PanelExtensions.DASHBOARD, "Cohort map", NOTHING);
        PanelExtensions.register("workfloweditor", PanelExtensions.VERSIONS, "Workflow Editor", NOTHING);
        assertEquals(List.of("Cohort map"), labels(PanelExtensions.DASHBOARD));
        assertEquals(List.of("Workflow Editor"), labels(PanelExtensions.VERSIONS));
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeAddsNoButton() {
        PanelExtensions.register("cohort", PanelExtensions.DASHBOARD, "Cohort map", NOTHING);
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertTrue(PanelExtensions.entitled(PanelExtensions.DASHBOARD).isEmpty());
        ModuleEntitlements.setForTest(Set.of("compliance", "cohort"));
        assertEquals(List.of("Cohort map"), labels(PanelExtensions.DASHBOARD));
    }

    @Test
    void entriesOfOneButtonComeInRegistrationOrder() {
        ModuleEntitlements.setForTest(Set.of("a", "b"));
        PanelExtensions.register("a", PanelExtensions.DASHBOARD, "First", NOTHING);
        PanelExtensions.register("b", PanelExtensions.DASHBOARD, "Second", NOTHING);
        assertEquals(List.of("First", "Second"), labels(PanelExtensions.DASHBOARD));
    }

    @Test
    void aModuleRegisteringTheSameEntryTwiceIsListedOnce() {
        ModuleEntitlements.setForTest(Set.of("cohort"));
        int[] ran = {0};
        PanelExtensions.register("cohort", PanelExtensions.DASHBOARD, "Cohort map", NOTHING);
        PanelExtensions.register("cohort", PanelExtensions.DASHBOARD, "Cohort map", () -> ran[0]++);
        assertEquals(1, PanelExtensions.entitled(PanelExtensions.DASHBOARD).size());
        PanelExtensions.entitled(PanelExtensions.DASHBOARD).get(0).action().run();
        assertEquals(1, ran[0]);
    }

    @Test
    void whoeverDrawsThePanelIsToldWhenAButtonArrivesOncePerOwner() {
        int[] told = {0};
        PanelExtensions.onChange("panel", () -> told[0] += 100);
        PanelExtensions.onChange("panel", () -> told[0]++);   // the panel drawn again: replaces
        PanelExtensions.register("cohort", PanelExtensions.DASHBOARD, "Cohort map", NOTHING);
        assertEquals(1, told[0]);
    }

    @Test
    void aButtonCarriesTheIconItsModuleDraws() {
        ModuleEntitlements.setForTest(Set.of("cohort"));
        javafx.scene.Group drawn = new javafx.scene.Group();
        PanelExtensions.register("cohort", PanelExtensions.DASHBOARD, "Cohort map", c -> drawn, NOTHING);
        assertEquals(drawn, PanelExtensions.entitled(PanelExtensions.DASHBOARD).get(0).icon()
            .apply(javafx.scene.paint.Color.WHITE));
    }

    @Test
    void aModuleButtonsAreFoundByModuleWhateverTheyGoWith() {
        ModuleEntitlements.setForTest(Set.of("library", "training"));
        PanelExtensions.register("library", PanelExtensions.VERSIONS, "Library", NOTHING);
        PanelExtensions.register("training", PanelExtensions.PROFILE, "Training", NOTHING);
        assertEquals(List.of("Library"), PanelExtensions.ofModule("library").stream().map(PanelExtensions.Entry::label).toList());
        assertEquals(List.of("Training"), PanelExtensions.ofModule("training").stream().map(PanelExtensions.Entry::label).toList());
        assertTrue(PanelExtensions.ofModule("cohort").isEmpty());
    }

    @Test
    void aButtonForAnOrganizationPanelFollowsNoButtonOfQTraceOwnPanel() {
        ModuleEntitlements.setForTest(Set.of("training"));
        PanelExtensions.register("training", PanelExtensions.PROFILE, "Training", NOTHING);
        assertTrue(PanelExtensions.entitled(PanelExtensions.DASHBOARD).isEmpty());
        assertTrue(PanelExtensions.entitled(PanelExtensions.VERSIONS).isEmpty());
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeHasNoButtonByModuleEither() {
        PanelExtensions.register("library", PanelExtensions.VERSIONS, "Library", NOTHING);
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertTrue(PanelExtensions.ofModule("library").isEmpty());
    }
}
