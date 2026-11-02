package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** qTrace's own mini-panel: the module that follows Stamp in the panel (the Logbook) stands right under Stamp. */
class MiniPanelDefaultLayoutTest {

    private static final Runnable NOTHING = () -> {};

    @AfterEach
    void clear() {
        PanelExtensions.clear();
        ModuleEntitlements.setForTest(null);
        PanelProfile.setForTest(null);
    }

    @Test
    void theLogbookStandsRightUnderStamp() {
        ModuleEntitlements.setForTest(Set.of("logbook"));
        PanelProfile.setForTest(null);
        PanelExtensions.register("logbook", PanelExtensions.CAPTURE, "Logbook", NOTHING);
        List<String> layout = QTraceMiniPanel.defaultLayout();
        assertEquals(PanelProfile.STAMP, layout.get(0));
        assertEquals("logbook", layout.get(1));
        assertEquals(List.of(PanelProfile.STAMP, "logbook"), QTraceMiniPanel.defaultTools().subList(0, 2));
    }

    @Test
    void aLogbookTheLicenceDoesNotIncludeIsNotInIt() {
        ModuleEntitlements.setForTest(Set.of("compliance"));
        PanelProfile.setForTest(null);
        PanelExtensions.register("logbook", PanelExtensions.CAPTURE, "Logbook", NOTHING);
        assertFalse(QTraceMiniPanel.defaultLayout().contains("logbook"));
    }

    @Test
    void theLogbookHasStampsGroupColour() {
        PanelExtensions.Entry logbook = new PanelExtensions.Entry("logbook", PanelExtensions.CAPTURE, "Logbook", null, NOTHING);
        PanelExtensions.Entry other = new PanelExtensions.Entry("x", PanelExtensions.VERSIONS, "X", null, NOTHING);
        assertEquals(QTracePanel.GROUP_TOOLS, QTracePanel.groupOf(logbook));
        assertEquals(QTracePanel.GROUP_WORKSPACE, QTracePanel.groupOf(other));
    }
}
