package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Optional;

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

    // ── Folder that already exists with another case (qtrace/, QTRACE/, Trace/…) ─────
    // Synology Drive, OneDrive or a Windows/macOS disk treat qTrace/ and qtrace/ as the same
    // name: creating qTrace/ next to an existing qtrace/ makes the sync client rename it
    // "qTrace_<host>_<date>_CaseConflict" on every export.

    @Test
    void resolveDir_reusesExistingFolderWithOtherCase(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("qtrace"));
        assertEquals(project.resolve("qtrace").resolve("trace"),
            QTraceConfig.resolveDir(true, project, QTraceConfig.TRACE_SUBDIR, FALLBACK));
    }

    @Test
    void resolveDir_reusesExistingSubfolderWithOtherCase(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("qTrace").resolve("GeoJSON"));
        assertEquals(project.resolve("qTrace").resolve("GeoJSON"),
            QTraceConfig.resolveDir(true, project, QTraceConfig.GEOJSON_SUBDIR, FALLBACK));
    }

    @Test
    void readDir_reusesExistingFolderWithOtherCase(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("qtrace").resolve("trace"));
        assertEquals(Optional.of(project.resolve("qtrace").resolve("trace")),
            QTraceConfig.readDir(true, project, QTraceConfig.TRACE_SUBDIR, FALLBACK));
    }

    @Test
    void createProjectDirs_intoExistingFolderWithOtherCase(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("qtrace"));
        QTraceConfig.createProjectDirs(project);

        assertTrue(Files.isDirectory(project.resolve("qtrace").resolve("trace")));
        assertEquals(1, Files.list(project).count(), "no second qTrace/ next to qtrace/");
    }

    @Test
    void exactCaseWins_whenBothExist(@TempDir Path project) throws Exception {
        Files.createDirectories(project.resolve("qtrace"));
        Files.createDirectories(project.resolve("qTrace"));
        assertEquals(project.resolve("qTrace"), QTraceConfig.projectQTraceDir(project));
    }

    // ── Reading side (Dashboard, version graph) ──────────────────────────────

    @Test
    void readDir_projectMode_withProject_isTheProjectSubfolder() {
        Path project = Path.of("/my/project");
        assertEquals(Optional.of(project.resolve("qTrace").resolve("trace")),
            QTraceConfig.readDir(true, project, QTraceConfig.TRACE_SUBDIR, FALLBACK));
    }

    @Test
    void readDir_projectMode_withoutProject_isEmpty_notTheFallback() {
        // Use Project Folder on + no project open: the Dashboard must ask for a project
        // instead of silently showing whatever sits in the configured fallback folder.
        assertEquals(Optional.empty(), QTraceConfig.readDir(true, null, QTraceConfig.TRACE_SUBDIR, FALLBACK));
    }

    @Test
    void readDir_projectModeOff_isTheConfiguredFolder() {
        assertEquals(Optional.of(FALLBACK),
            QTraceConfig.readDir(false, Path.of("/my/project"), QTraceConfig.TRACE_SUBDIR, FALLBACK));
    }

    // ── Writing side: every output honours Project Folder mode ─────────────

    @Test
    void outputDirs_followTheCurrentProject_whenProjectModeIsOn(@TempDir Path project) {
        QTraceConfig cfg = QTraceConfig.get();
        boolean was = cfg.isUseProjectFolder();
        try {
            cfg.setUseProjectFolder(true);
            QTraceConfig.setProjectDirSupplier(() -> project);
            assertEquals(project.resolve("qTrace/trace"), cfg.outputExportDir());
            assertEquals(project.resolve("qTrace/gitTrack"), cfg.outputClassifierDir());
            assertEquals(project.resolve("qTrace/geoJson"), cfg.outputTrainingDir());
            assertTrue(Files.isDirectory(project.resolve("qTrace/trace")), "created on demand");
        } finally {
            cfg.setUseProjectFolder(was);
            QTraceConfig.setProjectDirSupplier(() -> null);
        }
    }
}
