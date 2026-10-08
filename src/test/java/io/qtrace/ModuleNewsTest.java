package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModuleNewsTest {

    @Test
    void aModuleInstalledInANewerVersionThanTheOneShownIsNews() {
        assertEquals(Map.of("player", "1.1.0"),
            ModuleNews.updated(Map.of("player", "1.1.0", "cohort", "0.1.0"), Map.of("player", "1.0.0", "cohort", "0.1.0")));
    }

    @Test
    void aModuleNeverShownIsNotNews_itWasJustInstalled() {
        assertTrue(ModuleNews.updated(Map.of("player", "1.0.0"), Map.of()).isEmpty());
    }

    @Test
    void anOlderOrEqualVersionIsNotNews() {
        assertTrue(ModuleNews.updated(Map.of("player", "1.0.0"), Map.of("player", "1.0.0")).isEmpty());
        assertTrue(ModuleNews.updated(Map.of("player", "1.0.0"), Map.of("player", "1.2.0")).isEmpty());
    }

    @Test
    void whatIsInstalledBecomesWhatWasShown_keepingModulesNoLongerThere() {
        assertEquals(Map.of("player", "1.1.0", "cohort", "0.1.0", "gone", "0.3.0"),
            ModuleNews.afterShowing(Map.of("player", "1.1.0", "cohort", "0.1.0"), Map.of("player", "1.0.0", "gone", "0.3.0")));
    }

    @Test
    void theVersionsShownAreKeptOnDisk(@TempDir Path dir) {
        Path file = dir.resolve("modules-seen.json");
        assertNull(ModuleNews.read(file), "nothing remembered yet");
        ModuleNews.write(file, Map.of("player", "1.0.0"));
        assertEquals(Map.of("player", "1.0.0"), ModuleNews.read(file));
    }

    @Test
    void anUnreadableFileIsLikeNoFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("modules-seen.json");
        Files.writeString(file, "not json");
        assertNull(ModuleNews.read(file));
    }
}
