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

    static final String VERSION = "1.0.7";

    public static String getDisplayVersion() {
        QTracePlugin ep = QTracePluginManager.get();
        if (ep != null) {
            String epv = ep.getPluginVersion();
            if (epv != null) return epv;
        }
        return VERSION;
    }

    public static String getEditionLabel() {
        QTracePlugin ep = QTracePluginManager.get();
        if (ep != null) {
            String epVersion = ep.getPluginVersion();
            String v = epVersion != null ? epVersion : VERSION;
            return "qTrace Enterprise" + entitlementSuffix() + "  v" + v;
        }
        return "qTrace Core  v" + VERSION;
    }

    /**
     * Title suffix reflecting the license state: "" when active/absent, else
     * " (corrupted)" / " (expired)" / " (inactive)" depending on why it was downgraded.
     */
    static String entitlementSuffix() {
        if (!QTracePluginManager.hasEnterprise() || QTracePluginManager.isEntitled()) return "";
        String r = QTracePluginManager.inactiveReason();
        if ("corrupted".equals(r)) return " (corrupted)";
        if ("expired".equals(r))   return " (expired)";
        return " (inactive)";
    }

    /** True when the inactive state is a security failure (invalid/tampered signature) → show red. */
    static boolean entitlementIsError() {
        return QTracePluginManager.hasEnterprise()
            && !QTracePluginManager.isEntitled()
            && "corrupted".equals(QTracePluginManager.inactiveReason());
    }

    /**
     * Authoritative contributor identity for action attribution (one commit = one author).
     * Resolution order:
     *   1. valid Enterprise license holder (certified identity),
     *   2. configured validator name in QTraceConfig,
     *   3. OS login (System property user.name).
     * The OS login + machine are always kept separately for forensics; this is the
     * human-readable contributor shown on commit-graph nodes and in attributions.
     */
    public static String currentContributor() {
        QTracePlugin ep = QTracePluginManager.getEntitled();
        if (ep != null) {
            LicenseInfo li = ep.getActiveLicenseInfo();
            if (li != null && !li.expired() && li.name() != null && !li.name().isBlank())
                return li.name();
        }
        String configured = QTraceConfig.get().getValidatorName();
        if (configured != null && !configured.isBlank()) return configured;
        return System.getProperty("user.name", "unknown");
    }

    private final QuPathGUI qupath;
    private QTracePanel     panel;
    private QTraceDashboard dashboard;
    private QTraceCommitGraph commitGraph;
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

    /** Rebuilds the panel UI to reflect a changed entitlement (license downgrade). */
    public void refreshPanel() {
        if (panel != null && panel.isShowing()) panel.refresh();
    }

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

    /** Opens the commit-graph window for the current image's .qtrace (Enterprise feature). */
    public void showCommitGraph() {
        // Nothing to graph until there is a project, an open image, and at least one stamp (.qtrace).
        if (qupath.getProject() == null) {
            showGraphInfo(QTraceI18n.t("graph.info.noproject"));
            return;
        }
        if (qupath.getImageData() == null) {
            showGraphInfo(QTraceI18n.t("graph.info.noimage"));
            return;
        }
        File preselected = currentQtraceFile();
        if (preselected == null) {
            showGraphInfo(QTraceI18n.t("graph.info.nostamp"));
            return;
        }
        if (commitGraph == null || !commitGraph.isShowing()) {
            commitGraph = new QTraceCommitGraph(qupath);
            commitGraph.show(preselected);
        } else if (commitGraph.isIconified()) {
            commitGraph.front();
        } else {
            commitGraph.minimize();
        }
    }

    /** Resolves the .qtrace path for the current image, or null if none yet. */
    private File currentQtraceFile() {
        try {
            var imageData = logger.getCurrentImageData();
            if (imageData == null) return null;
            String base = imageData.getServer().getMetadata().getName()
                .replaceAll("[^a-zA-Z0-9._-]", "_");
            Path outFile = QTraceConfig.get().getExportDir().resolve(base + ".qtrace");
            return Files.exists(outFile) ? outFile.toFile() : null;
        } catch (Exception ignored) { return null; }
    }

    /** Non-blocking info alert shown when the commit graph has nothing to display. */
    private void showGraphInfo(String message) {
        Platform.runLater(() -> {
            javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
            alert.setTitle(QTraceI18n.t("graph.window.title"));
            alert.setHeaderText(null);
            alert.setContentText(message);
            if (qupath != null && qupath.getStage() != null)
                alert.initOwner(qupath.getStage());
            alert.show();
        });
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

    // ── Replay (Enterprise) ──────────────────────────────────────────────────

    public void openReplayDialog() {
        QTracePlugin ep = QTracePluginManager.getEntitled();
        if (ep != null) ep.replay(qupath, logger);
    }

    /**
     * Opens QuPath's Script Editor (if not already open) and loads {@code script} into it.
     * Uses reflection to stay compatible across QuPath 0.5.1–0.7.x.
     */
    /**
     * Opens QuPath's Script Editor and pre-loads {@code script} into it.
     * Inspection of DefaultScriptEditor (0.5.1) reveals:
     *   showEditor()              — shows the editor Stage
     *   getCurrentEditorControl() — returns the active ScriptEditorControl
     *   ScriptEditorControl.setText(String) — sets the editor content
     */
    public static void openInScriptEditor(QuPathGUI qupath, String script) {
        ScriptEditor se = qupath.getScriptEditor();
        if (se == null) return;
        try {
            // 1. Show the Script Editor window
            invokeMethod(se, "showEditor");

            // 2. Get the active editor control and set its text
            Object ctrl = invokeMethod(se, "getCurrentEditorControl");
            if (ctrl == null) ctrl = invokeMethod(se, "getEditorControl");
            if (ctrl != null) {
                try {
                    ctrl.getClass().getMethod("setText", String.class).invoke(ctrl, script);
                    return;
                } catch (Exception ignored) {}
            }

            // Fallback: scriptText StringProperty
            Object raw = findField(se, "scriptText", Object.class);
            if (raw instanceof javafx.beans.property.StringProperty sp) {
                sp.setValue(script);
            }
        } catch (Exception e) {
            System.err.println("[qTrace] openInScriptEditor: " + e.getMessage());
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
            exporter.setExtensions(collectLoadedExtensions());
            Path outFile = exporter.export(outDir);
            Path csvFile = exporter.appendToMasterCsv(outDir);

            if (panel != null) {
                panel.log(".qtrace written:");
                panel.log("  " + outFile.getFileName());
                panel.log("  CSV: " + csvFile.getFileName());
            }

            // Enterprise: build .qtcert chain-of-custody certificate (only when licensed & active)
            QTracePlugin ep = QTracePluginManager.getEntitled();
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
        QTracePlugin ep = QTracePluginManager.getEntitled();
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
        java.util.Collection<ClassifierRecord> classifiers = logger.getKnownClassifiers().values();
        if (panel != null) {
            panel.log("☁ Pushing to workspace…");
            panel.log("  · " + lastQtracePath.getFileName());
            panel.log("  · " + lastCertPath.getFileName());
            panel.log("  · chain.jsonl");
            for (ClassifierRecord clf : classifiers)
                panel.log("  · classifiers/" + clf.name + ".json");
            panel.setPushEnabled(false);
            panel.startPushProgress();
        }
        ep.pushToWorkspace(lastStamp, lastCertPath, chainLog, lastQtracePath, classifiers)
          .thenAccept(url -> {
              if (panel != null) panel.stopPushProgress();
              if (url != null && !url.startsWith("ERROR:")) {
                  if (panel != null) panel.log("☁ " + url);
              } else {
                  if (panel != null) {
                      panel.log("☁ Push failed: " + (url != null ? url : "network/license error"));
                      panel.setPushEnabled(true);
                  }
              }
          })
          .exceptionally(t -> {
              if (panel != null) {
                  panel.stopPushProgress();
                  panel.log("☁ Push error: " + t.getMessage());
                  panel.setPushEnabled(true);
              }
              return null;
          });
    }

    // ── Import .qTrace (Enterprise stub) ─────────────────────────────────────

    public void importAndReplay() {
        QTracePlugin plugin = QTracePluginManager.getEntitled();
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

    // ── Extension capture ─────────────────────────────────────────────────────

    private JsonArray collectLoadedExtensions() {
        JsonArray arr = new JsonArray();

        // QuPath 0.5.x: getLoadedExtensions() on QuPathGUI
        try {
            Method m = QuPathGUI.class.getMethod("getLoadedExtensions");
            java.util.Collection<?> exts = (java.util.Collection<?>) m.invoke(qupath);
            for (Object ext : exts) addExtToArray(arr, ext);
            if (arr.size() > 0) return arr;
        } catch (Exception ignored) {}

        // QuPath 0.7.x: ServiceLoader via extension classloader (getLoadedExtensions removed)
        try {
            ClassLoader cl = QTraceController.class.getClassLoader();
            Class<?> extClass = Class.forName("qupath.lib.gui.extensions.QuPathExtension", false, cl);
            @SuppressWarnings({"unchecked", "rawtypes"})
            java.util.ServiceLoader<?> sl = java.util.ServiceLoader.load((Class) extClass, cl);
            for (Object ext : sl) addExtToArray(arr, ext);
        } catch (Exception ignored) {}

        return arr;
    }

    private static void addExtToArray(com.google.gson.JsonArray arr, Object ext) {
        try {
            JsonObject ej = new JsonObject();
            ej.addProperty("name", (String) ext.getClass().getMethod("getName").invoke(ext));
            try {
                Object ver = ext.getClass().getMethod("getVersion").invoke(ext);
                if (ver != null) ej.addProperty("version", ver.toString());
            } catch (Exception ignored) {}
            arr.add(ej);
        } catch (Exception ignored) {}
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
        exporter.setExtensions(collectLoadedExtensions());
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
