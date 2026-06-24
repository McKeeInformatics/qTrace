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

package io.astraebio.qtrace;

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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Singleton
    private static volatile QTraceConfig instance;

    // Stored fields (null = use default)
    private String exportDir;
    private String classifierDir;
    private String trainingDir;
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

    // ── Path setters ─────────────────────────────────────────────────────────

    public void setExportDir(String p)      { this.exportDir       = blank(p); }
    public void setClassifierDir(String p)  { this.classifierDir   = blank(p); }
    public void setTrainingDir(String p)    { this.trainingDir     = blank(p); }

    // ── Validator ─────────────────────────────────────────────────────────────

    /** Returns the configured validator name, or empty string if not set. */
    public String getValidatorName()        { return validatorName != null ? validatorName : ""; }
    public void   setValidatorName(String v){ this.validatorName = blank(v); }

    // ── Enterprise license ────────────────────────────────────────────────────

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

    // ── Raw string getters (for the dialog text fields) ───────────────────────

    public String rawExportDir()      { return exportDir       != null ? exportDir       : ""; }
    public String rawClassifierDir()  { return classifierDir   != null ? classifierDir   : ""; }
    public String rawTrainingDir()    { return trainingDir     != null ? trainingDir     : ""; }

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
