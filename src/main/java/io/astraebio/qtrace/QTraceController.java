package io.astraebio.qtrace;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import javafx.application.Platform;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.ScriptEditor;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Main coordinator for QTrace.
 *
 * Compiled against QuPath 0.5.1 API (Java 17 class files, readable by Java 21 javac).
 * Runtime target is QuPath 0.7.x — API is a strict superset, fully compatible.
 * Protected/package-private methods are accessed via reflection at runtime.
 *
 * Captures QuPath analysis provenance (steps, classifiers, annotations) and
 * exports to .qtrace JSON sidecar after expert validation via ValidationStamper.
 */
public class QTraceController {

    static final String VERSION = "1.0.1";

    public static String getEditionLabel() {
        String edition = QTracePluginManager.hasEnterprise() ? "Enterprise" : "Core";
        return "qTrace " + edition + "  v" + VERSION;
    }

    private final QuPathGUI qupath;
    private QTracePanel     panel;
    private QTraceDashboard dashboard;
    private ActionLogger    logger;
    private boolean         scriptHookInstalled = false;

    // Script output capture — written from Logback thread, read from FX thread
    private volatile boolean          capturingOutput = false;
    private final List<String>        capturedOutput  = new CopyOnWriteArrayList<>();

    // Validation state
    private ValidationStamp lastStamp    = null;
    private Path            lastCertPath  = null;  // written by exportReport(), used by pushToWorkspace()
    private Path            lastQtracePath = null;

    // Recording state listeners (toolbar icon, etc.)
    private final List<Consumer<Boolean>> recordingListeners = new CopyOnWriteArrayList<>();

    public void addRecordingListener(Consumer<Boolean> l) { recordingListeners.add(l); }

    void fireRecordingState(boolean active) {
        recordingListeners.forEach(l -> Platform.runLater(() -> l.accept(active)));
    }

    public boolean isRecording() {
        return logger != null && logger.isAttached();
    }

    public QTraceController(QuPathGUI qupath) {
        this.qupath = qupath;
        // Logger starts immediately — panel-less, silent until panel is attached
        this.logger = new ActionLogger(qupath, null);
        ImageData<BufferedImage> current = qupath.getImageData();
        if (current != null) {
            logger.attach(current);
            fireRecordingState(true);
        }
        attachViewerListener();
    }

    // ── Panel lifecycle ──────────────────────────────────────────────────────

    public void showPanel() {
        if (panel == null || !panel.isShowing()) {
            panel = new QTracePanel(qupath, this);
            logger.setPanel(panel);
            syncPanelState();
        }
        panel.show();
        attachScriptEditorHook();
    }

    private void syncPanelState() {
        if (panel == null) return;
        boolean attached = logger.isAttached();
        int steps        = logger.getCapturedSteps().size();
        int preExisting  = logger.getPreExistingStepCount();
        int manual       = logger.getManualAnnotationCount();
        Platform.runLater(() -> {
            panel.setRecordingActive(attached);
            panel.updateStepCount(steps, preExisting, manual);
            panel.setRecordReady(steps > 0);
            if (attached && steps > 0)
                panel.log("qTrace already recording — " + steps + " step(s) captured before panel opened.");
            else if (attached)
                panel.log("qTrace recording — waiting for first action.");
        });
    }

    public void showDashboard() {
        if (dashboard == null || !dashboard.isShowing()) {
            dashboard = new QTraceDashboard(qupath);
            dashboard.show();
        } else if (dashboard.isIconified()) {
            dashboard.show();
        } else {
            dashboard.minimize();
        }
    }

    public void showPreferences() {
        QTraceSettingsDialog.show(qupath.getStage());
    }

    public void showAbout() {
        QTraceAboutDialog.show(qupath);
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
                if (logger != null) {
                    logger.attach(newData);
                    fireRecordingState(newData != null);
                }
                if (panel != null && panel.isShowing())
                    Platform.runLater(panel::refreshStatus);
            }

            @Override public void visibleRegionChanged(QuPathViewer v, java.awt.Shape s) {}
            @Override public void selectedObjectChanged(QuPathViewer v, PathObject o)     {}
            @Override public void viewerClosed(QuPathViewer v) {
                if (logger != null) logger.detach();
                fireRecordingState(false);
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

    public void recordTrace() {
        if (logger == null || !logger.hasSteps()) {
            if (panel != null) panel.log("Nothing to record — no steps captured yet.");
            return;
        }
        logger.refreshAllAnnotationCaptures();
        String currentStatus = readCurrentStatus();
        String qpdataHash = resolveQpdataHash();
        String defaultCaseId = resolveDefaultCaseId();
        ValidationStamper.show(qupath.getStage(), null, logger.getImageHash(), qpdataHash,
                               logger.computeClassifierFidelity(), currentStatus, defaultCaseId)
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
                        panel.setValidated(true, stamp.validator());
                    }
                    exportReport();
                },
                () -> { if (panel != null) panel.log("Record cancelled."); }
            );
    }

    /**
     * Hashes the .qpdata file for the currently open image so the stamp can
     * bind the certificate to the exact data state at validation time.
     * Returns null silently if no project is open or the file cannot be located.
     */
    private String resolveQpdataHash() {
        try {
            var imageData = logger.getCurrentImageData();
            if (imageData == null) return null;
            var project = qupath.getProject();
            if (project == null) return null;
            String imgName = imageData.getServer().getMetadata().getName();
            for (var entry : project.getImageList()) {
                if (!imgName.equals(entry.getImageName())) continue;
                Path entryPath = entry.getEntryPath();
                if (entryPath == null) continue;
                Path qpdata = entryPath.resolve("data.qpdata");
                if (Files.exists(qpdata))
                    return io.astraebio.qtrace.chain.Hashing.sha256Hex(qpdata);
            }
        } catch (Exception e) {
            System.err.println("[qTrace] resolveQpdataHash: " + e.getMessage());
        }
        return null;
    }

    private String resolveDefaultCaseId() {
        try {
            var project = qupath.getProject();
            if (project != null && project.getName() != null) return project.getName();
        } catch (Exception ignored) {}
        return "";
    }

    private String readCurrentStatus() {
        try {
            var imageData = logger.getCurrentImageData();
            if (imageData == null) return null;
            String imageName = imageData.getServer().getMetadata().getName();
            String base = imageName.replaceAll("[^a-zA-Z0-9._-]", "_");
            java.nio.file.Path outFile = QTraceConfig.get().getExportDir().resolve(base + ".qtrace");
            if (!java.nio.file.Files.exists(outFile)) return null;
            JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(outFile)).getAsJsonObject();
            return root.has("status") ? root.get("status").getAsString() : null;
        } catch (Exception ignored) { return null; }
    }

    public void exportReport() {
        if (logger == null || !logger.hasSteps()) {
            if (panel != null) panel.log("Nothing to export — capture steps first.");
            return;
        }
        try {
            Path outDir  = QTraceConfig.get().getExportDir();
            var exporter = new QTraceExporter(logger, null, lastStamp);
            Path outFile = exporter.export(outDir);
            Path csvFile = exporter.appendToMasterCsv(outDir);

            if (panel != null) {
                panel.log(".qtrace written:");
                panel.log("  " + outFile.getFileName());
                panel.log("  CSV: " + csvFile.getFileName());
            }

            // Enterprise: build .qtcert chain-of-custody certificate
            QTracePlugin ep = QTracePluginManager.get();
            lastQtracePath = outFile;
            lastCertPath   = null;
            if (ep != null && lastStamp != null) {
                try {
                    JsonObject qtroot = JsonParser.parseString(Files.readString(outFile)).getAsJsonObject();
                    JsonArray sessions = qtroot.getAsJsonArray("sessions");
                    JsonObject lastSession = sessions.get(sessions.size() - 1).getAsJsonObject();
                    JsonObject imageRoot = qtroot.getAsJsonObject("image");
                    Path certPath = ep.buildCertificate(lastStamp, lastSession, imageRoot, outDir);
                    if (certPath != null) {
                        lastCertPath = certPath;
                        if (panel != null) {
                            panel.log("  .qtcert: " + certPath.getFileName());
                            panel.setPushEnabled(true);
                        }
                    }
                } catch (Exception e) {
                    if (panel != null) panel.log("  .qtcert: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (panel != null) panel.log("Export error: " + e.getMessage());
        }
    }

    // ── Cloud workspace push (Enterprise) ────────────────────────────────────

    public void pushToWorkspace() {
        QTracePlugin ep = QTracePluginManager.get();
        if (ep == null) return;
        if (lastCertPath == null || lastQtracePath == null) {
            if (panel != null) panel.log("☁ Nothing to push — export first.");
            return;
        }
        // chain.jsonl is at case_<id>/chain.jsonl, cert at case_<id>/certs/<id>.qtcert
        Path chainLog = lastCertPath.getParent().getParent().resolve("chain.jsonl");
        if (!chainLog.toFile().exists()) {
            if (panel != null) panel.log("☁ chain.jsonl not found.");
            return;
        }
        if (panel != null) {
            panel.log("☁ Pushing to workspace…");
            panel.setPushEnabled(false);
        }
        ep.pushToWorkspace(lastStamp, lastCertPath, chainLog, lastQtracePath)
          .thenAccept(url -> {
              if (url != null && !url.startsWith("ERROR:")) {
                  if (panel != null) panel.log("☁ " + url);
              } else {
                  if (panel != null) {
                      panel.log("☁ Push failed: " + (url != null ? url : "network/license error"));
                      panel.setPushEnabled(true);
                  }
              }
          });
    }

    // ── Import .qTrace (Enterprise stub) ─────────────────────────────────────

    public void importAndReplay() {
        QTracePlugin plugin = QTracePluginManager.get();
        if (plugin != null) plugin.replay(qupath, logger);
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
        QTraceBatchExporter.start(qupath, this);
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

        String hash = logger.getImageHash();
        lastStamp = new ValidationStamp(
            validator, java.time.Instant.now(), scope, confidence, notes,
            null, hash, null,  // qpdataSha256: null on batch path (no QuPath project context)
            resolveDefaultCaseId(),
            logger.computeClassifierFidelity().name(), 1, "1-In Progress",
            null, null);  // signature + validatorKeyPub: unsigned (batch path, no dialog)

        Path exportDir = QTraceConfig.get().getExportDir();
        var  exporter  = new QTraceExporter(logger, null, lastStamp);
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
        lastStamp = null;
        if (panel != null) {
            panel.setValidated(false, "");
            panel.setRecordingActive(true);
        }
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public QuPathGUI       getQuPath()   { return qupath;    }
    public ActionLogger    getLogger()   { return logger;    }
    public ValidationStamp getLastStamp(){ return lastStamp;  }
}
