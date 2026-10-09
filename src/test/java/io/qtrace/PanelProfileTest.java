package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PanelProfileTest {

    private static final String PLAIN = "{\"version\":\"1.2.6\",\"files\":[]}";
    private static final String PROFILED = "{\"version\":\"1.2.6\",\"files\":[],\"panel\":{\"tools\":[\"training\",\"library\"]}}";

    @AfterEach
    void clear() {
        PanelProfile.setForTest(null);
    }

    @Test
    void aDescriptorWithoutAPanelMeansNoProfile() {
        assertNull(PanelProfile.parse(PLAIN));
        assertNull(PanelProfile.parse("{\"files\":[],\"panel\":null}"));
    }

    @Test
    void theProfileIsTheOrderedListOfTools() {
        assertEquals(List.of("training", "library"), PanelProfile.parse(PROFILED));
    }

    @Test
    void anEmptyProfileIsAProfileThatShowsNoTool() {
        assertEquals(List.of(), PanelProfile.parse("{\"files\":[],\"panel\":{\"tools\":[]}}"));
    }

    @Test
    void aToolIsListedOnceAndOddEntriesAreDropped() {
        assertEquals(List.of("training", "stamp"),
            PanelProfile.parse("{\"files\":[],\"panel\":{\"tools\":[\"training\",7,\"\",\"Stamp \",\"training\",null]}}"));
    }

    @Test
    void aPanelThatIsNotOneIsNoProfile() {
        assertNull(PanelProfile.parse("{\"files\":[],\"panel\":\"training\"}"));
        assertNull(PanelProfile.parse("{\"files\":[],\"panel\":{\"tools\":\"training\"}}"));
        assertNull(PanelProfile.parse("<html>502</html>"));
        assertNull(PanelProfile.parse(null));
    }

    @Test
    void withoutAProfileEveryToolShows() {
        assertTrue(PanelProfile.shows("dashboard", null));
        assertTrue(PanelProfile.shows("anything", null));
    }

    @Test
    void withAProfileOnlyItsToolsShow() {
        List<String> profile = List.of("training", "library");
        assertTrue(PanelProfile.shows("training", profile));
        assertFalse(PanelProfile.shows("dashboard", profile));
        assertFalse(PanelProfile.shows("stamp", List.of()));
    }

    @Test
    void theLastProfileIsKeptOnDisk(@TempDir Path dir) {
        Path file = dir.resolve("panel-profile.json");
        PanelProfile.write(file, List.of("training", "library"));
        assertEquals(List.of("training", "library"), PanelProfile.read(file));
    }

    @Test
    void noProfileRemovesTheOneKeptOnDisk(@TempDir Path dir) {
        Path file = dir.resolve("panel-profile.json");
        PanelProfile.write(file, List.of("training"));
        PanelProfile.write(file, null);
        assertFalse(Files.exists(file));
        assertNull(PanelProfile.read(file));
    }

    @Test
    void anUnreadableFileIsNoProfile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("panel-profile.json");
        Files.writeString(file, "not json");
        assertNull(PanelProfile.read(file));
    }

    @Test
    void listenersHearAChangeOfProfileOnly() {
        List<String> heard = new ArrayList<>();
        PanelProfile.setForTest(null);
        PanelProfile.onChange("test", () -> heard.add("changed"));
        PanelProfile.apply(List.of("training"), null);
        PanelProfile.apply(List.of("training"), null);
        assertEquals(List.of("changed"), heard);
        assertEquals(List.of("training"), PanelProfile.tools());
        assertTrue(PanelProfile.shows("training"));
        assertFalse(PanelProfile.shows("dashboard"));
        PanelProfile.apply(null, null);
        assertEquals(2, heard.size());
        assertTrue(PanelProfile.shows("dashboard"));
    }
}
