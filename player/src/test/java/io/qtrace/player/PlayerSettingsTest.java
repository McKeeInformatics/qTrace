package io.qtrace.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The player's own settings file: tolerant to read, atomic to write, saving each image by default. */
class PlayerSettingsTest {

    @TempDir Path dir;

    @Test
    void savesEachImageByDefaultWhenTheFileIsAbsent() {
        assertThat(new PlayerSettings(dir.resolve("player.json")).saveOnTargetChange()).isTrue();
    }

    @Test
    void theChoiceSurvivesAReload() {
        Path file = dir.resolve("sub/player.json");
        new PlayerSettings(file).setSaveOnTargetChange(false);
        assertThat(new PlayerSettings(file).saveOnTargetChange()).isFalse();
        new PlayerSettings(file).setSaveOnTargetChange(true);
        assertThat(new PlayerSettings(file).saveOnTargetChange()).isTrue();
    }

    @Test
    void aCorruptFileFallsBackOnTheDefault() throws Exception {
        Path file = dir.resolve("player.json");
        Files.writeString(file, "{ not json");
        assertThat(new PlayerSettings(file).saveOnTargetChange()).isTrue();
        Files.writeString(file, "[1,2]");
        assertThat(new PlayerSettings(file).saveOnTargetChange()).isTrue();
        Files.writeString(file, "{\"saveOnTargetChange\":\"maybe\"}");
        assertThat(new PlayerSettings(file).saveOnTargetChange()).isTrue();
    }

    @Test
    void writingLeavesNoTemporaryFileBehind() throws Exception {
        Path file = dir.resolve("player.json");
        new PlayerSettings(file).setSaveOnTargetChange(false);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("player.json");
        }
    }
}
