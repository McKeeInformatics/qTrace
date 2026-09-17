package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure resolution logic for the "Use Project Folder" mode — <project>/qTrace/{trace,geoJson,logs,gitTrack}.
 */
class QTraceConfigTest {

    private static final Path FALLBACK = Path.of("/fallback/dir");

    @Test
    void resolveDir_fallsBackToConfigured_whenProjectFolderModeOff() {
        Path resolved = QTraceConfig.resolveDir(false, Path.of("/some/project"), QTraceConfig.TRACE_SUBDIR, FALLBACK);
        assertEquals(FALLBACK, resolved);
    }

    @Test
    void resolveDir_fallsBackToConfigured_whenNoProjectOpen() {
        Path resolved = QTraceConfig.resolveDir(true, null, QTraceConfig.TRACE_SUBDIR, FALLBACK);
        assertEquals(FALLBACK, resolved);
    }

    @Test
    void resolveDir_resolvesEachSubfolder_underProjectQTraceDir() {
        Path projectBaseDir = Path.of("/my/project");
        assertEquals(projectBaseDir.resolve("qTrace").resolve("trace"),
            QTraceConfig.resolveDir(true, projectBaseDir, QTraceConfig.TRACE_SUBDIR, FALLBACK));
        assertEquals(projectBaseDir.resolve("qTrace").resolve("geoJson"),
            QTraceConfig.resolveDir(true, projectBaseDir, QTraceConfig.GEOJSON_SUBDIR, FALLBACK));
        assertEquals(projectBaseDir.resolve("qTrace").resolve("logs"),
            QTraceConfig.resolveDir(true, projectBaseDir, QTraceConfig.LOGS_SUBDIR, FALLBACK));
        assertEquals(projectBaseDir.resolve("qTrace").resolve("gitTrack"),
            QTraceConfig.resolveDir(true, projectBaseDir, QTraceConfig.GITTRACK_SUBDIR, FALLBACK));
    }

    @Test
    void createProjectDirs_createsAllFourSubfolders(@TempDir Path tempDir) throws Exception {
        QTraceConfig.createProjectDirs(tempDir);

        Path root = tempDir.resolve("qTrace");
        assertTrue(Files.isDirectory(root.resolve("trace")));
        assertTrue(Files.isDirectory(root.resolve("geoJson")));
        assertTrue(Files.isDirectory(root.resolve("logs")));
        assertTrue(Files.isDirectory(root.resolve("gitTrack")));
    }

}
