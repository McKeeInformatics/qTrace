/*
 * qTrace — QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package io.qtrace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Persistent configuration for QTrace export paths.
 * Lazily loaded from / saved to ~/.qTrace/config.json.
 *
 * All getters return a valid Path — falling back to the default
 * ~/Documents/QuPath/scripts/qtrace/ when the stored value is null or blank.
 */
public class QTraceConfig {

    private static final Path CONFIG_FILE =
        Path.of(System.getProperty("user.home"), ".qTrace", "config.json");

    private static final Path DEFAULT_DIR =
        Path.of(System.getProperty("user.home"), "Documents", "QuPath", "scripts", "qtrace");

    private static final Path DEFAULT_LOGS_DIR =
        Path.of(System.getProperty("user.home"), ".qTrace", "replay-logs");

    /** Live drafts (autosave + crash recovery) — machine-local, never exported or synced. */
    public static final Path DRAFTS_DIR =
        Path.of(System.getProperty("user.home"), ".qTrace", "drafts");

    // ── Project Folder mode — <project>/qTrace/{trace,geoJson,logs,gitTrack} ──────
    public static final String PROJECT_SUBDIR   = "qTrace";
    public static final String TRACE_SUBDIR     = "trace";
    public static final String GEOJSON_SUBDIR   = "geoJson";
    public static final String LOGS_SUBDIR      = "logs";
    public static final String GITTRACK_SUBDIR  = "gitTrack";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Singleton
    private static volatile QTraceConfig instance;

    // Stored fields (null = use default)
    private String exportDir;
    private String classifierDir;
    private String trainingDir;
    private String lastReplayBrowseDir; // last folder the Compliance Player's "Browse" opened from
    private String logsDir; // Player replay logs — null = ~/.qTrace/replay-logs/
    private Boolean useProjectFolder; // when true, store everything under <project>/qTrace/ instead of the paths above
    private String validatorName;
    private String licensePath;
    private String signingKeyPath; // path to qtrace-signing.key; null = default ~/.qTrace/qtrace-signing.key

    // Auto-update (null updateCheckEnabled = enabled by default)
    private Boolean updateCheckEnabled;
    private String  dismissedUpdateVersion; // version the user chose to skip

    // Activity report — confirm the data sent to Claude before each send
    // (null = ask by default; user can disable via "ne plus me demander" / Security settings)
    private Boolean reportConfirmBeforeSend;
    private String  reportLanguage; // language code for the generated report (null = UI locale)

    // Detection correction audit — null = prompt by default
    private Boolean promptDetectionNote;

    // Unstamped-image reminder on image close/switch — null = prompt by default
    private Boolean promptUnstampedReminder;

    // Live autosave of the capture + unstamped sessions — null = on by default
    private Boolean autosaveEnabled;

    private QTraceConfig() {}

    public static QTraceConfig get() {
        if (instance == null) {
            synchronized (QTraceConfig.class) {
                if (instance == null) instance = load();
            }
        }
        return instance;
    }

    // ── Path getters ─────────────────────────────────────────────────────────

    public Path getExportDir()      { return resolve(exportDir);       }
    public Path getClassifierDir()  { return resolve(classifierDir);   }
    public Path getTrainingDir()    { return resolve(trainingDir);     }

    /** Where the Player's "Browse" should start — the last folder it was used from, or the configured export dir until then. */
    public Path getLastReplayBrowseDir() {
        return (lastReplayBrowseDir != null && !lastReplayBrowseDir.isBlank())
            ? Path.of(lastReplayBrowseDir) : getExportDir();
    }

    /** Where the Player writes replay logs when Project Folder mode is off — falls back to ~/.qTrace/replay-logs/. */
    public Path getLogsDir() {
        return (logsDir != null && !logsDir.isBlank()) ? Path.of(logsDir) : DEFAULT_LOGS_DIR;
    }

    // ── Path setters ─────────────────────────────────────────────────────────

    public void setExportDir(String p)      { this.exportDir       = blank(p); }
    public void setClassifierDir(String p)  { this.classifierDir   = blank(p); }
    public void setTrainingDir(String p)    { this.trainingDir     = blank(p); }
    public void setLastReplayBrowseDir(String p) { this.lastReplayBrowseDir = blank(p); }
    public void setLogsDir(String p)        { this.logsDir         = blank(p); }

    // ── Project Folder mode ───────────────────────────────────────────────────

    /**
     * When enabled, qTrace stores its output under {@code <project>/qTrace/} — with
     * {@code trace/}, {@code geoJson/}, {@code logs/} and {@code gitTrack/} subfolders,
     * created on demand — instead of the paths configured above. Default: on.
     */
    public boolean isUseProjectFolder()        { return useProjectFolder == null || useProjectFolder; }
    public void    setUseProjectFolder(boolean b) { this.useProjectFolder = b; }

    /**
     * {@code <projectBaseDir>/qTrace} — or the folder already there under another case
     * ({@code qtrace/}, {@code QTRACE/}). Synology Drive, OneDrive and Windows/macOS disks treat
     * those names as one: creating {@code qTrace/} next to {@code qtrace/} makes a sync client
     * rename it {@code qTrace_<host>_<date>_CaseConflict} on every export.
     */
    public static Path projectQTraceDir(Path projectBaseDir) {
        return existingChild(projectBaseDir, PROJECT_SUBDIR);
    }

    /** {@code <projectBaseDir>/qTrace/<subdir>}, each level reusing a folder that differs only by case. */
    private static Path projectSubdir(Path projectBaseDir, String subdir) {
        return existingChild(projectQTraceDir(projectBaseDir), subdir);
    }

    /** {@code parent/name}, unless only a case variant of it exists — then that one. */
    private static Path existingChild(Path parent, String name) {
        Path exact = parent.resolve(name);
        if (Files.isDirectory(exact) || !Files.isDirectory(parent)) return exact;
        try (var children = Files.list(parent)) {
            return children
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                .sorted()
                .findFirst()
                .orElse(exact);
        } catch (IOException e) {
            return exact;
        }
    }

    /**
     * Resolves a path that may be redirected under {@code <projectBaseDir>/qTrace/<subdir>}
     * when Project Folder mode is on and a project is open; otherwise returns {@code fallback}.
     * Pure/static so it's testable without the config singleton.
     */
    public static Path resolveDir(boolean useProjectFolder, Path projectBaseDir, String subdir, Path fallback) {
        if (useProjectFolder && projectBaseDir != null) {
            return projectSubdir(projectBaseDir, subdir);
        }
        return fallback;
    }

    public Path resolveExportDir(Path projectBaseDir)     { return resolveDir(isUseProjectFolder(), projectBaseDir, TRACE_SUBDIR,    getExportDir());     }
    public Path resolveClassifierDir(Path projectBaseDir) { return resolveDir(isUseProjectFolder(), projectBaseDir, GITTRACK_SUBDIR, getClassifierDir()); }
    public Path resolveTrainingDir(Path projectBaseDir)   { return resolveDir(isUseProjectFolder(), projectBaseDir, GEOJSON_SUBDIR,  getTrainingDir());   }
    public Path resolveLogsDir(Path projectBaseDir)       { return resolveDir(isUseProjectFolder(), projectBaseDir, LOGS_SUBDIR,     getLogsDir());       }

    /**
     * Folder a reader (Dashboard, version graph) should look in. Unlike {@link #resolveDir},
     * Project Folder mode without an open project yields empty — the caller asks for a
     * project instead of silently showing whatever sits in the configured fallback folder.
     */
    public static Optional<Path> readDir(boolean useProjectFolder, Path projectBaseDir, String subdir, Path fallback) {
        if (useProjectFolder) {
            return projectBaseDir == null ? Optional.empty()
                : Optional.of(projectSubdir(projectBaseDir, subdir));
        }
        return Optional.ofNullable(fallback);
    }

    // The open QuPath project's folder, supplied by QTraceController (null = no project).
    private static volatile Supplier<Path> projectDirSupplier = () -> null;

    public static void setProjectDirSupplier(Supplier<Path> supplier) {
        projectDirSupplier = supplier != null ? supplier : () -> null;
    }

    /** Base folder of the open QuPath project, or null. */
    public static Path currentProjectDir() {
        try { return projectDirSupplier.get(); } catch (Exception e) { return null; }
    }

    // ── Effective folders — what every reader/writer must use ─────────────────
    // Project Folder mode redirects to <project>/qTrace/<subdir> (created on demand) when a
    // project is open; otherwise the configured folder. Writers keep the configured folder
    // as a fallback when no project is open, so no provenance is ever lost.

    public Path outputExportDir()     { return ensured(resolveExportDir(currentProjectDir())); }
    public Path outputClassifierDir() { return ensured(resolveClassifierDir(currentProjectDir())); }
    public Path outputTrainingDir()   { return ensured(resolveTrainingDir(currentProjectDir())); }

    /** Where readers look for .qtrace files; empty = Project Folder mode but no project open. */
    public Optional<Path> readExportDir() {
        return readDir(isUseProjectFolder(), currentProjectDir(), TRACE_SUBDIR, getExportDir());
    }

    private Path ensured(Path dir) {
        if (dir != null && isUseProjectFolder() && currentProjectDir() != null) {
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
        }
        return dir;
    }

    /** Creates the four Project Folder mode subfolders under {@code <projectBaseDir>/qTrace/} if missing. */
    public static void createProjectDirs(Path projectBaseDir) throws IOException {
        for (String sub : new String[] { TRACE_SUBDIR, GEOJSON_SUBDIR, LOGS_SUBDIR, GITTRACK_SUBDIR }) {
            Files.createDirectories(projectSubdir(projectBaseDir, sub));
        }
    }

    // ── Validator ─────────────────────────────────────────────────────────────

    /** Returns the configured validator name, or empty string if not set. */
    public String getValidatorName()        { return validatorName != null ? validatorName : ""; }
    public void   setValidatorName(String v){ this.validatorName = blank(v); }

    // ── Compliance license ────────────────────────────────────────────────────

    public String getLicensePath()         { return licensePath != null ? licensePath : ""; }
    public void   setLicensePath(String p) { this.licensePath = blank(p); }

    // ── Signing key ───────────────────────────────────────────────────────────

    public String getSigningKeyPath()         { return signingKeyPath != null ? signingKeyPath : ""; }
    public void   setSigningKeyPath(String p) { this.signingKeyPath = blank(p); }

    // ── Auto-update ─────────────────────────────────────────────────────────────

    public boolean isUpdateCheckEnabled()        { return updateCheckEnabled == null || updateCheckEnabled; }
    public void    setUpdateCheckEnabled(boolean b) { this.updateCheckEnabled = b; }

    public String getDismissedUpdateVersion()       { return dismissedUpdateVersion != null ? dismissedUpdateVersion : ""; }
    public void   setDismissedUpdateVersion(String v) { this.dismissedUpdateVersion = blank(v); }

    // ── Activity report — security/audit ──────────────────────────────────────

    /** Whether to show the pre-send confirmation (data preview) before each report. Default: yes. */
    public boolean isReportConfirmBeforeSend()        { return reportConfirmBeforeSend == null || reportConfirmBeforeSend; }
    public void    setReportConfirmBeforeSend(boolean b) { this.reportConfirmBeforeSend = b; }

    /** Language code for the generated report; defaults to the UI locale when unset. */
    public String getReportLanguage() {
        return (reportLanguage != null && ReportLanguages.isKnown(reportLanguage))
            ? reportLanguage : ReportLanguages.defaultCode();
    }
    public void setReportLanguage(String code) { this.reportLanguage = blank(code); }

    // ── Detection correction audit ────────────────────────────────────────────

    public boolean isPromptDetectionNote()           { return promptDetectionNote == null || promptDetectionNote; }
    public void    setPromptDetectionNote(boolean b) { this.promptDetectionNote = b; }

    // ── Unstamped-image reminder ──────────────────────────────────────────────

    /** Whether to prompt to stamp when unstamped modifications are detected while closing/switching an image. Default: yes. */
    public boolean isPromptUnstampedReminder()           { return promptUnstampedReminder == null || promptUnstampedReminder; }
    public void    setPromptUnstampedReminder(boolean b) { this.promptUnstampedReminder = b; }

    public boolean isAutosaveEnabled()                   { return autosaveEnabled == null || autosaveEnabled; }
    public void    setAutosaveEnabled(boolean b)         { this.autosaveEnabled = b; }

    // ── Raw string getters (for the dialog text fields) ───────────────────────

    public String rawExportDir()      { return exportDir       != null ? exportDir       : ""; }
    public String rawClassifierDir()  { return classifierDir   != null ? classifierDir   : ""; }
    public String rawTrainingDir()    { return trainingDir     != null ? trainingDir     : ""; }
    public String rawLogsDir()        { return logsDir         != null ? logsDir         : ""; }

    public static String defaultDirString() { return DEFAULT_DIR.toString(); }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, GSON.toJson(this));
        } catch (IOException ignored) {}
    }

    private static QTraceConfig load() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = Files.readString(CONFIG_FILE);
                QTraceConfig cfg = GSON.fromJson(json, QTraceConfig.class);
                if (cfg != null) return cfg;
            }
        } catch (Exception ignored) {}
        return new QTraceConfig();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Path resolve(String stored) {
        return (stored != null && !stored.isBlank()) ? Path.of(stored) : DEFAULT_DIR;
    }

    private static String blank(String s) {
        return (s != null && !s.isBlank()) ? s.strip() : null;
    }
}
