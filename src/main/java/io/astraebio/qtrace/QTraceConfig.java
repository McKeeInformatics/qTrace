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
    private String metaScriptDir;
    private String exportDir;
    private String classifierDir;
    private String trainingDir;
    private String validatorName;

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

    public Path getMetaScriptDir()  { return resolve(metaScriptDir);  }
    public Path getExportDir()      { return resolve(exportDir);       }
    public Path getClassifierDir()  { return resolve(classifierDir);   }
    public Path getTrainingDir()    { return resolve(trainingDir);     }

    // ── Path setters ─────────────────────────────────────────────────────────

    public void setMetaScriptDir(String p)  { this.metaScriptDir  = blank(p); }
    public void setExportDir(String p)      { this.exportDir       = blank(p); }
    public void setClassifierDir(String p)  { this.classifierDir   = blank(p); }
    public void setTrainingDir(String p)    { this.trainingDir     = blank(p); }

    // ── Validator ─────────────────────────────────────────────────────────────

    /** Returns the configured validator name, or empty string if not set. */
    public String getValidatorName()        { return validatorName != null ? validatorName : ""; }
    public void   setValidatorName(String v){ this.validatorName = blank(v); }

    // ── Raw string getters (for the dialog text fields) ───────────────────────

    public String rawMetaScriptDir()  { return metaScriptDir  != null ? metaScriptDir  : ""; }
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
