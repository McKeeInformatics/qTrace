package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolWelcomesTest {

    @AfterEach
    void clear() {
        ToolWelcomes.clear();
    }

    // ── Which welcomes are still to be read ─────────────────────────────────────

    @Test
    void aToolNeverReadIsToBeRead() {
        assertEquals(Set.of("player"), ToolWelcomes.unread(Map.of("player", 0), Map.of(), Set.of("player")));
    }

    @Test
    void aToolReadAtThisRevisionIsNot() {
        assertTrue(ToolWelcomes.unread(Map.of("player", 2), Map.of("player", 2), Set.of("player")).isEmpty());
    }

    @Test
    void aRevisionRaisedInTheBackOfficeMakesItToBeReadAgain() {
        assertEquals(Set.of("player"), ToolWelcomes.unread(Map.of("player", 3), Map.of("player", 2), Set.of("player")));
    }

    @Test
    void aToolThatIsNotOnThisWorkstationHasNothingToShow() {
        assertTrue(ToolWelcomes.unread(Map.of("cohort", 0), Map.of(), Set.of("player")).isEmpty());
    }

    @Test
    void whatWasReadIsKeptOnDisk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("welcomes-read.json");
        assertTrue(ToolWelcomes.read(file).isEmpty());
        ToolWelcomes.write(file, Map.of("player", 3));
        assertEquals(Map.of("player", 3), ToolWelcomes.read(file));
        Files.writeString(file, "not json");
        assertTrue(ToolWelcomes.read(file).isEmpty());
    }

    // ── What the panel is told ───────────────────────────────────────────────

    @Test
    void thePanelIsToldWhichToolsAreMarked() {
        int[] told = {0};
        ToolWelcomes.onChange(this, () -> told[0]++);
        ToolWelcomes.setUnread(Set.of("player", "versiongraph"));
        assertTrue(ToolWelcomes.isUnread("player"));
        assertFalse(ToolWelcomes.isUnread("cohort"));
        assertEquals(1, told[0]);
        ToolWelcomes.setUnread(Set.of("player", "versiongraph"));
        assertEquals(1, told[0], "nothing changed: nobody is told");
    }

    @Test
    void aMarkIsOnlyShownWhenSomethingCanPlayTheWelcome() {
        ToolWelcomes.setUnread(Set.of("player"));
        assertFalse(ToolWelcomes.open("player"), "no module plays welcomes: nothing opens");
        List<String> opened = new ArrayList<>();
        ToolWelcomes.register(opened::add);
        assertTrue(ToolWelcomes.open("player"));
        assertEquals(List.of("player"), opened);
    }
}
