package io.astraebio.qtrace;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.ScriptEditor;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.stage.FileChooser;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.ProjectIO;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Main coordinator for QTrace.
 *
 * Compiled against QuPath 0.5.1 API (Java 17 class files, readable by Java 21 javac).
 * Runtime target is QuPath 0.7.x — API is a strict superset, fully compatible.
 * Protected/package-private methods are accessed via reflection at runtime.
 *
 * Phase 1  : panel lifecycle, viewer listener.
 * Phase 2  : ActionLogger wired — real-time step capture + SHA-256.
 * Phase 2.5: Script Editor hook — auto-captures every Run click as a workflow step.
 *            Output captured via Logback appender on DefaultScriptEditor logger.
 * Phase 3  : MetaScriptGenerator (stub).
 * Phase 4  : GitBridge (stub).
 * Phase 5  : ValidationStamper (stub).
 * Phase 6  : QTraceExporter (stub).
 */
public class QTraceController {

    static final String VERSION = "0.6.7";

    private final QuPathGUI qupath;
    private QTracePanel     panel;
    private ActionLogger    logger;
    private boolean         scriptHookInstalled = false;

    // Script output capture — written from Logback thread, read from FX thread
    private volatile boolean          capturingOutput = false;
    private final List<String>        capturedOutput  = new CopyOnWriteArrayList<>();

    // Phase 4 + 5 state ──────────────────────────────────────────────────────
    private String          lastGitHash = null;
    private ValidationStamp lastStamp   = null;

    public QTraceController(QuPathGUI qupath) {
        this.qupath = qupath;
        attachViewerListener();
    }

    // ── Panel lifecycle ──────────────────────────────────────────────────────

    public void showPanel() {
        if (panel == null || !panel.isShowing()) {
            panel  = new QTracePanel(qupath, this);
            logger = new ActionLogger(qupath, panel);

            ImageData<BufferedImage> current = qupath.getImageData();
            if (current != null) logger.attach(current);
        }
        panel.show();
        attachScriptEditorHook();
    }

    public void showDashboard() {
        var p = QTracePluginManager.get();
        if (p == null) {
            showEnterpriseAlert("Dashboard");
            return;
        }
        p.showDashboard(qupath);
    }

    public void showPreferences() {
        QTraceSettingsDialog.show(qupath.getStage());
    }

    public void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(qupath.getStage());
        alert.setTitle("About QTrace");
        alert.setHeaderText("QTrace  v" + VERSION);
        javafx.scene.image.Image logo = QTracePanel.loadLogo();
        if (logo != null) {
            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(logo);
            iv.setFitHeight(64);
            iv.setPreserveRatio(true);
            alert.setGraphic(iv);
        }
        alert.setContentText(
            "Workflow Provenance & Certification for QuPath\n\n"
          + "Active phases:\n"
          + "  • Phase 2 : WorkflowStep capture + SHA-256\n"
          + "  • Phase 2.5: Script Editor hook + output capture\n"
          + "  • Phase 3 : MetaScriptGenerator (deduplicated Groovy replay)\n"
          + "  • Phase 4 : GitBridge (JGit commit — no external Git needed)\n"
          + "  • Phase 5 : ValidationStamper (expert sign-off dialog)\n"
          + "  • Phase 6 : QTraceExporter (.qtrace JSON sidecar)\n\n"
          + "Developed for Astraea Bio\n"
          + "QuPath target: 0.7.x"
        );
        alert.showAndWait();
    }

    private void showEnterpriseAlert(String feature) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(qupath.getStage());
        alert.setTitle("qTrace Enterprise");
        alert.setHeaderText(feature + " — Enterprise feature");
        alert.setContentText(feature + " requires qTrace Enterprise.\n\nContact astraebio.com to upgrade.");
        alert.showAndWait();
    }

    // ── Script Editor hook — Phase 2.5 ──────────────────────────────────────

    /**
     * Hooks the Script Editor by observing {@code runningTask} ObjectProperty.
     *
     * null → nonNull : script started  — start Logback capture + add WorkflowStep.
     * nonNull → null : script finished — stop capture after 150 ms flush window.
     *
     * Output capture uses a Logback appender on the logger
     * {@code qupath.lib.gui.scripting.DefaultScriptEditor}, which receives every
     * {@code print()} / {@code println()} from running Groovy scripts.
     */
    private void attachScriptEditorHook() {
        if (scriptHookInstalled) return;

        Platform.runLater(() -> {
            try {
                ScriptEditor se = qupath.getScriptEditor();
                if (se == null) {
                    if (panel != null)
                        panel.log("Script hook: Script Editor not open — reopen panel after opening it.");
                    return;
                }

                Object rawRunningTask = findField(se, "runningTask", Object.class);
                if (!(rawRunningTask instanceof javafx.beans.value.ObservableValue<?> runningTask)) {
                    if (panel != null)
                        panel.log("Script hook: runningTask not observable (type="
                            + (rawRunningTask == null ? "null" : rawRunningTask.getClass().getSimpleName()) + ").");
                    return;
                }

                Object rawScriptText = findField(se, "scriptText", Object.class);
                installLogbackAppender();

                runningTask.addListener((obs, oldVal, newVal) -> {

                    if (newVal != null && oldVal == null) {
                        // Script just started — begin capturing log output
                        capturedOutput.clear();
                        capturingOutput = true;
                        if (logger != null) logger.setScriptRunning(true);

                        String text = null;
                        if (rawScriptText instanceof javafx.beans.value.ObservableValue<?> stProp) {
                            Object v = stProp.getValue();
                            text = (v instanceof String s) ? s : null;
                        }
                        if (text != null && !text.isBlank()) captureScriptRun(text, se);

                    } else if (newVal == null && oldVal != null) {
                        // Script just finished — wait 150 ms for trailing log messages to flush
                        javafx.animation.PauseTransition pause =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
                        pause.setOnFinished(e -> {
                            capturingOutput = false;
                            if (logger != null) logger.setScriptRunning(false);
                            if (!capturedOutput.isEmpty() && panel != null) {
                                capturedOutput.forEach(line -> panel.log("  out> " + line));
                            }
                        });
                        pause.play();
                    }
                });

                scriptHookInstalled = true;
                if (panel != null)
                    panel.log("Script Editor hook installed — runs + output will be recorded.");

            } catch (Exception e) {
                if (panel != null) panel.log("Script hook error: " + e.getMessage());
            }
        });
    }

    /**
     * Installs a Logback appender on the {@code qupath.lib.gui.scripting.DefaultScriptEditor}
     * logger. Every log message while {@code capturingOutput} is true goes into
     * {@code capturedOutput}.
     */
    private void installLogbackAppender() {
        try {
            Object factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof ch.qos.logback.classic.LoggerContext ctx)) {
                if (panel != null) panel.log("  [warn] Logback not found — output capture unavailable.");
                return;
            }

            ch.qos.logback.classic.Logger scriptLogger =
                ctx.getLogger("qupath.lib.gui.scripting.DefaultScriptEditor");

            AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
                @Override
                protected void append(ILoggingEvent event) {
                    if (capturingOutput) capturedOutput.add(event.getFormattedMessage());
                }
            };
            appender.setContext(ctx);
            appender.setName("qtrace-capture");
            appender.start();
            scriptLogger.addAppender(appender);

        } catch (Exception e) {
            if (panel != null) panel.log("  [warn] Logback appender failed: " + e.getMessage());
        }
    }

    /** Adds a WorkflowStep and logs path for the current script run. */
    private void captureScriptRun(String scriptText, Object scriptEditor) {
        try {
            String name = "Untitled";
            String path = null;

            Object tab = invokeMethod(scriptEditor, "getCurrentScriptTab");
            if (tab != null) {
                Object n = invokeMethod(tab, "getName");
                if (n instanceof String s) name = s;
                Object f = invokeMethod(tab, "getFile");
                if (f instanceof File file) path = file.getAbsolutePath();
            }

            ImageData<BufferedImage> imageData = qupath.getImageData();
            if (imageData == null) return;

            var step = new DefaultScriptableWorkflowStep("Script: " + name, scriptText);
            imageData.getHistoryWorkflow().addStep(step);

            if (panel != null && path != null) panel.log("  path: " + path);

        } catch (Exception e) {
            if (panel != null) panel.log("Script capture error: " + e.getMessage());
        }
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> T findField(Object obj, String name, Class<T> type) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return type.cast(f.get(obj));
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeMethod(Object obj, String methodName) {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Method m = cls.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return m.invoke(obj);
            } catch (NoSuchMethodException e) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // ── Viewer listener ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void attachViewerListener() {
        qupath.getViewer().addViewerListener(new QuPathViewerListener() {

            @Override
            public void imageDataChanged(QuPathViewer viewer,
                                         ImageData<BufferedImage> oldData,
                                         ImageData<BufferedImage> newData) {
                if (logger != null) logger.attach(newData);
                if (panel  != null && panel.isShowing()) {
                    Platform.runLater(panel::refreshStatus);
                }
            }

            @Override public void visibleRegionChanged(QuPathViewer v, java.awt.Shape s) {}
            @Override public void selectedObjectChanged(QuPathViewer v, PathObject o)     {}
            @Override public void viewerClosed(QuPathViewer v) {
                if (logger != null) logger.detach();
            }
        });
    }

    // ── Image helpers ────────────────────────────────────────────────────────

    public boolean hasActiveImage()    { return qupath.getImageData() != null; }

    public String getCurrentImageName() {
        var data = qupath.getImageData();
        return (data == null) ? "(No image open)" : data.getServer().getMetadata().getName();
    }

    // ── Action stubs ─────────────────────────────────────────────────────────

    public void generateMetaScript() {
        if (logger == null || !logger.hasSteps()) {
            if (panel != null) panel.log("Nothing to generate — no steps captured yet.");
            return;
        }
        try {
            // Sync final annotation state (name/class/color/description/locked) before
            // generating — QuPath doesn't always fire hierarchy events on property edits.
            logger.refreshAllAnnotationCaptures();

            Path outDir  = QTraceConfig.get().getMetaScriptDir();
            Path outFile = new MetaScriptGenerator(logger).generate(outDir);

            // ── Git commit (Phase 4) ─────────────────────────────────────────
            String imageName = logger.getCurrentImageData()
                                     .getServer().getMetadata().getName();
            String hash      = logger.getImageHash();
            String commitMsg = buildCommitMessage(imageName, hash,
                                                  logger.getCapturedSteps().size());
            lastGitHash = new GitBridge(outDir).commit(outFile, commitMsg);

            if (panel != null) {
                panel.log("Meta-Script committed:");
                panel.log("  file: " + outFile.getFileName());
                panel.log("  git : " + lastGitHash);
                if (hash != null) panel.log("  img : " + hash.substring(0, 16) + "...");
                panel.log("→ Click 'Validate & Stamp' to sign this capture.");
                panel.setScriptReady(true);
            }
        } catch (Exception e) {
            if (panel != null) panel.log("MetaScript/Git error: " + e.getMessage());
        }
    }

    private static String buildCommitMessage(String imageName, String hash, int steps) {
        return "QTrace capture: " + imageName + "\n\n"
             + "Image   : " + imageName + "\n"
             + "SHA-256 : " + (hash != null ? hash : "(pending)") + "\n"
             + "Steps   : " + steps + "\n"
             + "QTrace  : v" + VERSION;
    }

    public void validateAndStamp() {
        if (logger == null || !logger.hasSteps()) {
            if (panel != null) panel.log("Nothing to validate — generate a Meta-Script first.");
            return;
        }
        if (lastGitHash == null) {
            if (panel != null) panel.log("Generate a Meta-Script first (creates the Git commit to stamp).");
            return;
        }

        var p = QTracePluginManager.get();
        if (p == null) { showEnterpriseAlert("Validate & Stamp"); return; }
        p.showValidationDialog(qupath.getStage(), lastGitHash, logger.getImageHash(),
                               logger.computeClassifierFidelity())
            .ifPresentOrElse(
                stamp -> {
                    lastStamp = stamp;
                    if (panel != null) {
                        panel.log("Validation stamp recorded:");
                        panel.log("  validator  : " + stamp.validator());
                        panel.log("  scope      : " + stamp.scope());
                        panel.log("  confidence : " + stamp.confidence());
                        if (!stamp.notes().isEmpty())
                            panel.log("  notes      : " + stamp.notes());
                        panel.log("→ Click 'Export .qtrace' to write the JSON sidecar.");
                        panel.setValidated(true, stamp.validator());
                    }
                },
                () -> { if (panel != null) panel.log("Validation cancelled."); }
            );
    }

    public void exportReport() {
        if (logger == null || !logger.hasSteps()) {
            if (panel != null) panel.log("Nothing to export — capture steps first.");
            return;
        }
        if (lastGitHash == null) {
            if (panel != null) panel.log("Generate a Meta-Script first (Git commit required).");
            return;
        }
        try {
            Path outDir  = QTraceConfig.get().getExportDir();
            var exporter = new QTraceExporter(logger, lastGitHash, lastStamp);
            Path outFile = exporter.export(outDir);
            Path csvFile = exporter.appendToMasterCsv(outDir);

            if (panel != null) {
                panel.log(".qtrace sidecar written:");
                panel.log("  " + outFile.getFileName());
                panel.log("  CSV: " + csvFile.getFileName());
                if (lastStamp == null)
                    panel.log("  (no validation stamp — run 'Validate & Stamp' first)");
            }
        } catch (Exception e) {
            if (panel != null) panel.log("Export error: " + e.getMessage());
        }
    }

    // ── Import & Replay (Phase 3.5) ──────────────────────────────────────────

    /**
     * Shows a FileChooser and loads the selected .groovy or .qtrace into the
     * Script Editor. The script is NOT run automatically — the hook captures
     * it when the user clicks Run, maintaining the normal recording flow.
     */
    public void importAndReplay() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Script for Replay");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("QTrace & Groovy", "*.groovy", "*.qtrace"),
            new FileChooser.ExtensionFilter("Groovy scripts", "*.groovy"),
            new FileChooser.ExtensionFilter("QTrace sidecars", "*.qtrace")
        );
        File initDir = QTraceConfig.get().getMetaScriptDir().toFile();
        if (initDir.exists()) chooser.setInitialDirectory(initDir);

        File selected = chooser.showOpenDialog(qupath.getStage());
        if (selected == null) return;

        try {
            if (selected.getName().endsWith(".qtrace")) {
                loadQTraceFile(selected);
            } else {
                loadGroovyFile(selected);
            }
        } catch (Exception e) {
            if (panel != null) panel.log("Import error: " + e.getMessage());
        }
    }

    /** Reads a .groovy file, opens it in the Script Editor, and auto-opens the recorded project/image. */
    private void loadGroovyFile(File file) throws Exception {
        String content = Files.readString(file.toPath());
        openInScriptEditor(file.getName(), content);
        if (panel != null) {
            panel.log("Script imported: " + file.getName());
            panel.log("  → Review in Script Editor, then click Run to replay.");
        }
        // Parse provenance header for project path and image name
        String imageName   = null;
        String projectPath = null;
        for (String line : content.split("\n")) {
            if (line.startsWith("// Image") && line.contains(":")) {
                int idx = line.indexOf(": ");
                if (idx < 0) idx = line.indexOf(":");
                if (idx >= 0) imageName = line.substring(idx + 1).strip();
            } else if (line.startsWith("// Project") && line.contains(":")) {
                int idx = line.indexOf(": ");
                if (idx < 0) idx = line.indexOf(":");
                if (idx >= 0) projectPath = line.substring(idx + 1).strip();
            } else if (!line.startsWith("//") && !line.isBlank()) {
                break;
            }
        }
        tryOpenProjectAndImage(projectPath, imageName);
    }

    /**
     * Parses a .qtrace sidecar, shows provenance (including SHA-256 mismatch
     * warning if the current image differs), reconstructs the script from
     * captured step fragments, and opens it in the Script Editor.
     */
    /** Returns the last element of sessions[], or null if absent/empty. */
    private static JsonObject latestSession(JsonObject root) {
        if (root == null || !root.has("sessions") || root.get("sessions").isJsonNull()) return null;
        JsonArray sessions = root.getAsJsonArray("sessions");
        if (sessions.size() == 0) return null;
        return sessions.get(sessions.size() - 1).getAsJsonObject();
    }

    private void loadQTraceFile(File file) throws Exception {
        String json = Files.readString(file.toPath());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        if (panel != null) panel.log("=== .qtrace import: " + file.getName() + " ===");

        // Format 2.0: image is at root, session-level data is in sessions[-1]
        JsonObject session = latestSession(root);
        // Fallback: format <2.0 kept everything at root — treat root as session
        JsonObject sessionOrRoot = (session != null) ? session : root;

        // ── Provenance display ───────────────────────────────────────────────
        if (root.has("image") && !root.get("image").isJsonNull()) {
            JsonObject img = root.getAsJsonObject("image");
            String importedHash = img.has("sha256") ? img.get("sha256").getAsString() : null;
            String importedName = img.has("name")   ? img.get("name").getAsString()   : "?";

            if (panel != null) panel.log("  image : " + importedName);

            // SHA-256 check against current image
            String currentHash = (logger != null) ? logger.getImageHash() : null;
            if (importedHash != null && currentHash != null) {
                if (importedHash.equals(currentHash)) {
                    if (panel != null) panel.log("  SHA-256: MATCH ✓ — same source image");
                } else {
                    if (panel != null) {
                        panel.log("  SHA-256: MISMATCH ⚠");
                        panel.log("    recorded : " + importedHash.substring(0, 16) + "...");
                        panel.log("    current  : " + currentHash.substring(0, 16) + "...");
                        panel.log("  WARNING: script was recorded on a different image.");
                    }
                }
            } else if (importedHash != null && panel != null) {
                panel.log("  SHA-256: " + importedHash.substring(0, 16) + "...");
            }
        }

        if (sessionOrRoot.has("git") && !sessionOrRoot.get("git").isJsonNull()) {
            JsonObject git = sessionOrRoot.getAsJsonObject("git");
            if (panel != null && git.has("commit"))
                panel.log("  git   : " + git.get("commit").getAsString());
        }

        if (sessionOrRoot.has("validation") && !sessionOrRoot.get("validation").isJsonNull()) {
            JsonObject val = sessionOrRoot.getAsJsonObject("validation");
            if (panel != null)
                panel.log("  valid : " + val.get("validator").getAsString()
                    + " / " + val.get("scope").getAsString()
                    + " / " + val.get("confidence").getAsString());
        }

        if (session != null && panel != null) {
            String user    = session.has("user")    ? session.get("user").getAsString()    : null;
            String machine = session.has("machine") ? session.get("machine").getAsString() : null;
            if (user != null)
                panel.log("  session: " + user + (machine != null ? "@" + machine : "")
                    + " (session " + (root.getAsJsonArray("sessions").size()) + ")");
        }

        // Extract project path + image name for auto-open
        String importedImageName   = null;
        String importedProjectPath = null;

        if (root.has("image") && !root.get("image").isJsonNull()) {
            JsonObject imgObj = root.getAsJsonObject("image");
            if (imgObj.has("name")) importedImageName = imgObj.get("name").getAsString();
        }

        if (sessionOrRoot.has("project") && !sessionOrRoot.get("project").isJsonNull()) {
            JsonObject proj = sessionOrRoot.getAsJsonObject("project");
            if (panel != null && proj.has("name"))
                panel.log("  project: " + proj.get("name").getAsString());
            if (proj.has("path")) importedProjectPath = proj.get("path").getAsString();
            if (proj.has("images")) {
                JsonArray imgs = proj.getAsJsonArray("images");
                if (imgs.size() > 0 && panel != null) {
                    int shown = Math.min(imgs.size(), 5);
                    for (int i = 0; i < shown; i++)
                        panel.log("    [" + i + "] " + imgs.get(i).getAsString());
                    if (imgs.size() > 5)
                        panel.log("    … and " + (imgs.size() - 5) + " more");
                }
            }
        }

        // ── Extract embedded classifier models from latest session ───────────
        java.util.Map<String, String> embeddedClassifiers = new java.util.LinkedHashMap<>();
        JsonElement classifiersEl = sessionOrRoot.has("pixel_classifiers")
            ? sessionOrRoot.get("pixel_classifiers") : null;
        if (classifiersEl != null && classifiersEl.isJsonArray()) {
            for (var el : classifiersEl.getAsJsonArray()) {
                JsonObject clf = el.getAsJsonObject();
                if (!clf.has("name") || !clf.has("classifier_json")) continue;
                String clfName    = clf.get("name").getAsString();
                String clfContent = clf.getAsJsonObject("classifier_json").toString();
                embeddedClassifiers.put(clfName, clfContent);
                if (panel != null)
                    panel.log("  classifier: " + clfName
                        + (clf.has("classifier_type") ? " (" + clf.get("classifier_type").getAsString() + ")" : ""));
            }
        }

        // ── Reconstruct script from step fragments ───────────────────────────
        StringBuilder sb = new StringBuilder();
        sb.append("// Replayed from: ").append(file.getName()).append("\n");
        String qtraceVer = sessionOrRoot.has("qtrace_version")
            ? sessionOrRoot.get("qtrace_version").getAsString() : null;
        if (qtraceVer != null)
            sb.append("// QTrace v").append(qtraceVer).append("\n");
        sb.append("\nimport static qupath.lib.gui.scripting.QPEx.*\n\n");

        JsonElement stepsEl = sessionOrRoot.has("steps") ? sessionOrRoot.get("steps") : null;
        if (stepsEl != null && stepsEl.isJsonArray()) {
            JsonArray steps = stepsEl.getAsJsonArray();
            int scriptSteps = 0;
            java.util.Set<String> restoredClassifiers = new java.util.LinkedHashSet<>();
            for (var el : steps) {
                JsonObject step = el.getAsJsonObject();
                if (step.has("script_fragment") && !step.get("script_fragment").isJsonNull()) {
                    String frag = step.get("script_fragment").getAsString();
                    if (!frag.isBlank()) {
                        for (var entry : embeddedClassifiers.entrySet()) {
                            if (!restoredClassifiers.contains(entry.getKey())
                                    && frag.contains("\"" + entry.getKey() + "\"")) {
                                sb.append(buildClassifierRestoreScript(entry.getKey(), entry.getValue()));
                                restoredClassifiers.add(entry.getKey());
                            }
                        }
                        sb.append("// ── ").append(step.get("command").getAsString()).append(" ──\n");
                        sb.append(frag.strip()).append("\n\n");
                        scriptSteps++;
                    }
                }
            }
            if (panel != null) panel.log("  steps : " + scriptSteps + " scriptable step(s) loaded");
        }

        String name = file.getName().replace(".qtrace", "_replay.groovy");
        openInScriptEditor(name, sb.toString());
        if (panel != null) panel.log("  → Review in Script Editor, then click Run to replay.");
        tryOpenProjectAndImage(importedProjectPath, importedImageName);
    }

    /** Opens content in the Script Editor via the public ScriptEditor interface. */
    private void openInScriptEditor(String name, String content) {
        ScriptEditor se = qupath.getScriptEditor();
        if (se == null) {
            if (panel != null)
                panel.log("  Script Editor not open — please open it first (Automate menu).");
            return;
        }
        se.showScript(name, content);
    }

    // ── Classifier restore script builder ────────────────────────────────────

    private static String buildClassifierRestoreScript(String clfName, String jsonContent) {
        String safe = clfName.replaceAll("[^a-zA-Z0-9_]", "_");
        String b64  = java.util.Base64.getEncoder().encodeToString(
            jsonContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "// ── Restore pixel classifier: " + clfName + " ──\n"
             + "try {\n"
             + "    def _b64_" + safe + " = \"" + b64 + "\"\n"
             + "    def _json_" + safe + " = new String(java.util.Base64.getDecoder().decode(_b64_" + safe + "), \"UTF-8\")\n"
             + "    def _dir_" + safe + " = getProject().getPath().getParent()\n"
             + "        .resolve(\"classifiers\").resolve(\"pixel_classifiers\")\n"
             + "    java.nio.file.Files.createDirectories(_dir_" + safe + ")\n"
             + "    java.nio.file.Files.writeString(_dir_" + safe + ".resolve(\"" + clfName + ".json\"), _json_" + safe + ")\n"
             + "    println(\"[QTrace] Classifier '" + clfName + "' restored.\")\n"
             + "} catch (Exception _e_" + safe + ") {\n"
             + "    println(\"[QTrace] WARNING: classifier restore failed — \" + _e_" + safe + ".getMessage())\n"
             + "}\n\n";
    }

    // ── Import helpers — auto-open project + image ───────────────────────────

    /**
     * After importing a .groovy or .qtrace file, try to open the recorded
     * QuPath project (if different from the current one) and navigate to the
     * recorded image.  Fails gracefully: logs instructions if the API method
     * is unavailable or the project/image is not found on disk.
     */
    private void tryOpenProjectAndImage(String projectPath, String imageName) {
        if ((projectPath == null || projectPath.isBlank()) && (imageName == null || imageName.isBlank())) return;
        Platform.runLater(() -> {
            try {
                if (projectPath != null && !projectPath.isBlank()) {
                    Path pPath = Path.of(projectPath);
                    if (!Files.exists(pPath)) {
                        if (panel != null) panel.log("  ⚠ Recorded project not found on disk: " + projectPath);
                        openImageInCurrentProject(imageName);
                        return;
                    }
                    var current = qupath.getProject();
                    boolean same = current != null && current.getPath() != null
                        && current.getPath().toAbsolutePath().equals(pPath.toAbsolutePath());
                    if (!same) {
                        if (panel != null) panel.log("  → Opening recorded project: " + pPath.getFileName());
                        boolean opened = tryOpenProject(pPath.toFile());
                        if (opened && imageName != null && !imageName.isBlank()) {
                            javafx.animation.PauseTransition delay =
                                new javafx.animation.PauseTransition(javafx.util.Duration.millis(1500));
                            String img = imageName;
                            delay.setOnFinished(e -> Platform.runLater(() -> openImageInCurrentProject(img)));
                            delay.play();
                        } else if (!opened) {
                            if (panel != null) {
                                panel.log("  ⚠ Auto-open project not available — open manually:");
                                panel.log("    " + projectPath);
                                if (imageName != null) panel.log("  Then open image: " + imageName);
                            }
                        }
                        return;
                    }
                }
                openImageInCurrentProject(imageName);
            } catch (Exception e) {
                if (panel != null) panel.log("  Auto-open error: " + e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private boolean tryOpenProject(File projectFile) {
        try {
            // Load the Project object from disk (ProjectIO is in qupath-core, always available)
            Project<BufferedImage> project =
                (Project<BufferedImage>) ProjectIO.loadProject(projectFile, BufferedImage.class);
            if (project == null) return false;

            // QuPath 0.7.x: loadProject(Project<BufferedImage>)
            // QuPath 0.5.x: setProject(Project<BufferedImage>) as fallback
            for (String name : new String[]{"loadProject", "setProject"}) {
                Method m = findMethodWithParam(qupath.getClass(), name, Project.class);
                if (m != null) {
                    m.invoke(qupath, project);
                    return true;
                }
            }
        } catch (Exception e) {
            if (panel != null) panel.log("  openProject error: " + e.getMessage());
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void openImageInCurrentProject(String imageName) {
        if (imageName == null || imageName.isBlank()) return;
        try {
            var project = qupath.getProject();
            if (project == null) {
                if (panel != null) panel.log("  ⚠ No project open — cannot navigate to image.");
                return;
            }
            var match = project.getImageList().stream()
                .filter(e -> {
                    String n = e.getImageName();
                    return n.equals(imageName) || imageName.contains(n) || n.contains(imageName);
                })
                .findFirst();
            match.ifPresentOrElse(
                entry -> {
                    try {
                        Method m = findMethodWithParam(qupath.getClass(),
                            "openImageEntry", ProjectImageEntry.class);
                        if (m != null) {
                            m.invoke(qupath, entry);
                            if (panel != null) panel.log("  ✓ Opened image: " + entry.getImageName());
                        } else {
                            if (panel != null) panel.log(
                                "  ⚠ Auto-navigate unavailable — open image manually: " + imageName);
                        }
                    } catch (Exception ex) {
                        if (panel != null) panel.log("  Image open error: " + ex.getMessage());
                    }
                },
                () -> { if (panel != null) panel.log("  ⚠ Image not found in project: " + imageName); }
            );
        } catch (Exception e) {
            if (panel != null) panel.log("  Project lookup error: " + e.getMessage());
        }
    }

    private static Method findMethodWithParam(Class<?> cls, String name, Class<?> paramType) {
        while (cls != null) {
            try {
                Method m = cls.getDeclaredMethod(name, paramType);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    // ── Batch export ─────────────────────────────────────────────────────────

    public void startBatchExport() {
        var p = QTracePluginManager.get();
        if (p == null) { showEnterpriseAlert("Batch Import"); return; }
        p.startBatch(qupath, this);
    }

    /**
     * Non-interactive generate + stamp + export in one call.
     * Must be invoked on the JavaFX thread (CompletableFuture + Platform.runLater in the batch engine).
     */
    public String programmaticExport(String validator, String scope,
                                     String confidence, String notes) throws Exception {
        if (logger == null || !logger.hasSteps())
            throw new IllegalStateException("No steps captured for this image");

        logger.refreshAllAnnotationCaptures();

        Path outDir  = QTraceConfig.get().getMetaScriptDir();
        Path outFile = new MetaScriptGenerator(logger).generate(outDir);

        String imageName = logger.getCurrentImageData().getServer().getMetadata().getName();
        String hash      = logger.getImageHash();
        String commitMsg = buildCommitMessage(imageName, hash, logger.getCapturedSteps().size());
        lastGitHash = new GitBridge(outDir).commit(outFile, commitMsg);

        lastStamp = new ValidationStamp(
            validator, java.time.Instant.now(), scope, confidence, notes,
            lastGitHash, hash, logger.computeClassifierFidelity().name());

        Path exportDir = QTraceConfig.get().getExportDir();
        var  exporter  = new QTraceExporter(logger, lastGitHash, lastStamp);
        Path out       = exporter.export(exportDir);
        exporter.appendToMasterCsv(exportDir);
        return out.getFileName().toString();
    }

    /**
     * Opens a project image entry. Must be called on the JavaFX thread.
     */
    @SuppressWarnings("unchecked")
    public void openEntry(ProjectImageEntry<?> entry) {
        try {
            Method m = findMethodWithParam(qupath.getClass(), "openImageEntry", ProjectImageEntry.class);
            if (m != null) m.invoke(qupath, entry);
        } catch (Exception e) {
            if (panel != null) panel.log("openEntry error: " + e.getMessage());
        }
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    public void resetCapture() {
        if (logger == null) return;
        logger.resetCapture();
        lastGitHash = null;
        lastStamp   = null;
        if (panel != null) {
            panel.setScriptReady(false);
            panel.setValidated(false, "");
            panel.setRecordingActive(true);
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public QuPathGUI       getQuPath()   { return qupath;    }
    public ActionLogger    getLogger()   { return logger;    }
    public String          getGitHash()  { return lastGitHash; }
    public ValidationStamp getLastStamp(){ return lastStamp;  }
}
