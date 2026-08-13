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
    private String pinHash;       // SHA-256 hex of the user's PIN, null = no PIN set
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
     * When enabled, the Player (and eventually other qTrace output) stores everything under
     * {@code <project>/qTrace/} — created on demand with subfolders per kind of output
     * ({@code Logs/} today) — instead of the paths configured above. Default: off.
     */
    public boolean isUseProjectFolder()        { return useProjectFolder != null && useProjectFolder; }
    public void    setUseProjectFolder(boolean b) { this.useProjectFolder = b; }

    // ── Validator ─────────────────────────────────────────────────────────────

    /** Returns the configured validator name, or empty string if not set. */
    public String getValidatorName()        { return validatorName != null ? validatorName : ""; }
    public void   setValidatorName(String v){ this.validatorName = blank(v); }

    // ── Compliance license ────────────────────────────────────────────────────

    public String getLicensePath()         { return licensePath != null ? licensePath : ""; }
    public void   setLicensePath(String p) { this.licensePath = blank(p); }

    // ── PIN protection ────────────────────────────────────────────────────────

    public boolean hasPinSet()          { return pinHash != null && !pinHash.isBlank(); }
    public String  getPinHash()         { return pinHash != null ? pinHash : ""; }
    public void    setPinHash(String h) { this.pinHash = (h == null || h.isBlank()) ? null : h; }

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
