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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.InteractiveObjectImporter;
import qupath.lib.gui.scripting.ScriptEditor;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

    // Read from the JAR manifest (Implementation-Version, set to the Gradle `version` at
    // build time) instead of a hardcoded literal — see the identical fix + rationale on
    // QTraceCompliancePlugin.COMPLIANCE_VERSION: a literal here silently goes stale on every
    // release bump, breaking the auto-updater's version comparison.
    static final String VERSION = resolveVersion();

    private static String resolveVersion() {
        return JarVersion.of(QTraceController.class);
    }

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
            return "qTrace Compliance" + entitlementSuffix() + "  v" + v;
        }
        return "qTrace Core  v" + VERSION;
    }

    /**
     * Title suffix reflecting the license state: "" when active/absent, else
     * " (corrupted)" / " (expired)" / " (inactive)" depending on why it was downgraded.
     */
    static String entitlementSuffix() {
        if (!QTracePluginManager.hasCompliance() || QTracePluginManager.isEntitled()) return "";
        String r = QTracePluginManager.inactiveReason();
        if ("corrupted".equals(r)) return " (corrupted)";
        if ("expired".equals(r))   return " (expired)";
        return " (inactive)";
    }

    /** True when the inactive state is a security failure (invalid/tampered signature) → show red. */
    static boolean entitlementIsError() {
        return QTracePluginManager.hasCompliance()
            && !QTracePluginManager.isEntitled()
            && "corrupted".equals(QTracePluginManager.inactiveReason());
    }

    /**
     * Authoritative contributor identity for action attribution (one commit = one author).
     * Resolution order:
     *   1. valid Compliance license holder (certified identity),
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
    // Live draft of the open image (autosave, unstamped sessions, crash recovery).
    private DraftManager    drafts;
    // The running controller — lets static entry points (Compliance Player) reach its draft.
    private static volatile QTraceController active;
    private boolean         scriptHookInstalled = false;

    // Script output capture — written from Logback thread, read from FX thread
    private volatile boolean          capturingOutput = false;
    private final List<String>        capturedOutput  = new CopyOnWriteArrayList<>();

    // Validation state
    private ValidationStamp lastStamp    = null;
    private Path            lastCertPath  = null;  // written by exportReport(), used by pushToWorkspace()
    private Path            lastQtracePath = null;
    private Path            lastThumbnailPath = null;
    private Path            lastGeojsonPath = null; // manual annotations GeoJSON — lives in QTraceConfig.outputTrainingDir(), not outDir
    // Captured-step count at the moment of lastStamp — imageHash alone (pixel content)
    // doesn't change when new steps/annotations are added to the same image, so it can't
    // tell "stamped" from "stamped, then more work happened" on its own.
    private int              lastStampStepCount = -1;

    // "Don't ask again this session" for the forgotten-stamp reminder — in-memory only,
    // resets on QuPath restart by design.
    private boolean skipStampReminder = false;

    // Recording state listeners (toolbar icon, etc.)
    private final List<Consumer<Boolean>> recordingListeners = new CopyOnWriteArrayList<>();

    public void addRecordingListener(Consumer<Boolean> l) { recordingListeners.add(l); }

    void fireRecordingState(boolean active) {
        recordingListeners.forEach(l -> Platform.runLater(() -> l.accept(active)));
    }

    public boolean isRecording() {
        return logger != null && logger.isAttached();
    }

    // The one controller of this QuPath session, for the modules loaded next to Core
    // (onboarding / welcome, loader.md § 17) — they have no reference to QTraceExtension.
    private static volatile QTraceController current;

    /** The controller created by QTraceExtension, or null before Core is installed. */
    public static QTraceController current() { return current; }

    public QTraceController(QuPathGUI qupath) {
        this.qupath = qupath;
        current = this;
        // Project Folder mode resolves every qTrace folder against the open project.
        QTraceConfig.setProjectDirSupplier(() -> {
            var project = qupath.getProject();
            return project != null && project.getPath() != null ? project.getPath().getParent() : null;
        });
        ActivityLog.add("[QTrace " + VERSION + "] Initialized.");
        // Logger starts immediately — panel-less; its lines are kept by ActivityLog until the panel opens
        this.logger = new ActionLogger(qupath, null);
        // Upload availability depends on the image SHA-256, computed asynchronously;
        // re-check once it's ready instead of leaving the button stuck disabled.
        logger.setOnHashReady(this::refreshPushAvailability);
        drafts = new DraftManager(qupath, logger, e -> { if (panel != null) panel.setAutosaveStatus(e); });
        logger.setOnChanged(drafts::markDirty);
        active = this;
        ImageData<BufferedImage> current = qupath.getImageData();
        if (current != null) attachImage(current);
        attachViewerListener();
        installExitHooks();
    }

    // ── Live draft: autosave, unstamped sessions, crash recovery ────────────

    /** Attaches the logger to an image, then either resumes its orphaned draft or starts a new one. */
    private void attachImage(ImageData<BufferedImage> data) {
        logger.attach(data);
        fireRecordingState(data != null);
        if (data == null) { drafts.end(); return; }
        Optional<io.qtrace.draft.DraftStore.Draft> orphan = drafts.findOrphan(data);
        if (orphan.isPresent()) {
            // Not tracked until the user chooses — a fresh draft would overwrite the orphan.
            drafts.end();
            Platform.runLater(() -> offerRecovery(data, orphan.get()));
        } else {
            drafts.begin(data);
        }
    }

    /**
     * Leaving the current image (switch, close, QuPath exit): what was captured since the
     * last commit becomes an unstamped session of the .qtrace, and the draft is dropped.
     * If writing fails the draft stays on disk, so the work is offered back next time.
     */
    private void leaveImage() {
        if (logger == null || !logger.isAttached()) return;
        if (commitUnstamped()) drafts.end();
    }

    /** Commits the open image's uncommitted capture as an unstamped session. True unless it failed. FX thread. */
    private boolean commitUnstamped() {
        if (!QTraceConfig.get().isAutosaveEnabled() || logger == null || !logger.isAttached()) return true;
        if (!logger.hasSteps() || !drafts.hasUncommittedChanges()) {
            drafts.markCommitted();
            return true;
        }
        try {
            var exporter = new QTraceExporter(logger, null, null);
            exporter.setExtensions(collectLoadedExtensions());
            exporter.setSessionId(drafts.sessionId());
            Path out = exporter.export(QTraceConfig.get().outputExportDir());
            drafts.markCommitted();
            ActivityLog.add(QTraceI18n.f("autosave.committed", out.getFileName()));
            return true;
        } catch (Exception e) {
            System.err.println("[qTrace] unstamped session not written: " + e.getMessage());
            ActivityLog.add("Unstamped session not written — the draft is kept: " + e.getMessage());
            return false;
        }
    }

    /** QuPath closing: commit after QuPath's own save prompt; on SIGTERM, at least flush the draft. */
    private void installExitHooks() {
        var stage = qupath.getStage();
        if (stage != null) {
            stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> {
                leaveImage();
                drafts.close();
            });
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> drafts.flushNow(), "qtrace-draft-flush"));
    }

    /**
     * A draft survived its QuPath (crash, kill, power loss): offer to put the image and the
     * capture back exactly as they were, or to keep the work as an unstamped session and
     * start again from the image as saved. Nothing is dropped silently — closing the dialog
     * keeps the work. FX thread.
     */
    private void offerRecovery(ImageData<BufferedImage> data, io.qtrace.draft.DraftStore.Draft draft) {
        if (logger.getCurrentImageData() != data) return;   // moved on; asked again next time
        String name  = data.getServer().getMetadata().getName();
        int    steps = draft.state().capturedSteps.size();
        String when  = "?";
        try {
            when = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.parse(draft.header().get("last_saved_at").getAsString()));
        } catch (Exception ignored) {}
        boolean qpdataChanged = drafts.qpdataChangedSince(draft, data);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (qupath.getStage() != null) alert.initOwner(qupath.getStage());
        alert.setTitle(QTraceI18n.t("recover.title"));
        alert.setHeaderText(QTraceI18n.f("recover.header", name));
        String content = draft.snapshot() != null
            ? QTraceI18n.f("recover.content", steps, when)
            : QTraceI18n.f("recover.content.noSnapshot", steps, when);
        if (qpdataChanged) content += "\n\n" + QTraceI18n.t("recover.content.qpdataChanged");
        alert.setContentText(content);
        ButtonType btnRestore = new ButtonType(QTraceI18n.t("recover.restore"), ButtonBar.ButtonData.OK_DONE);
        ButtonType btnKeep    = new ButtonType(QTraceI18n.t("recover.keep"),    ButtonBar.ButtonData.CANCEL_CLOSE);
        if (qpdataChanged) alert.getButtonTypes().setAll(btnKeep);
        else               alert.getButtonTypes().setAll(btnRestore, btnKeep);
        alert.getDialogPane().setMinWidth(520);

        Optional<ButtonType> result = alert.showAndWait();
        boolean restore = !qpdataChanged && result.isPresent() && result.get() == btnRestore;
        restoreDraft(data, draft, !restore);
    }

    /**
     * Puts the draft back: image snapshot (objects, history, type, stains, properties) read in
     * the background, then applied on the FX thread before the logger resumes the capture.
     * With {@code keepOnly}, the restored work is committed as an unstamped session and the
     * image goes back to its saved .qpdata.
     */
    private void restoreDraft(ImageData<BufferedImage> data, io.qtrace.draft.DraftStore.Draft draft,
                              boolean keepOnly) {
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return draft.snapshot() != null ? DraftManager.readSnapshot(draft, data) : null;
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            })
            .whenComplete((restored, err) -> Platform.runLater(() -> {
                if (logger.getCurrentImageData() != data) return;   // draft stays; offered again later
                if (err != null) {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    Path kept = drafts.setAside(draft);
                    showRecoveryError(cause, kept);
                    drafts.begin(data);
                    return;
                }
                logger.detach();
                if (restored != null) {
                    applyImage(data, restored);
                    data.setChanged(true);
                }
                logger.attach(data);
                logger.restoreState(draft.state());
                drafts.adopt(data, draft);
                if (keepOnly && commitUnstamped() && restored != null) revertToSaved(data);
                if (panel != null && panel.isShowing()) {
                    panel.refreshStatus();
                    refreshIntegrity();
                }
                fireRecordingState(true);
            }));
    }

    private void showRecoveryError(Throwable cause, Path keptAt) {
        System.err.println("[qTrace] draft restore failed: " + cause);
        Alert a = new Alert(Alert.AlertType.ERROR);
        if (qupath.getStage() != null) a.initOwner(qupath.getStage());
        a.setTitle(QTraceI18n.t("recover.title"));
        a.setHeaderText(QTraceI18n.f("recover.failed", String.valueOf(cause.getMessage())));
        a.setContentText(String.valueOf(keptAt != null ? keptAt : QTraceConfig.DRAFTS_DIR));
        a.showAndWait();
    }

    /** Back to the image as saved on disk (or empty when it was never saved), with a fresh capture. */
    private void revertToSaved(ImageData<BufferedImage> data) {
        try {
            logger.detach();
            Path qpdata = drafts.qpdataPath(data);
            if (qpdata != null) {
                applyImage(data, PathIO.readImageData(qpdata, null, data.getServer(), BufferedImage.class));
            } else {
                data.getHierarchy().clearAll();
                data.getHistoryWorkflow().clear();
            }
            data.setChanged(false);
        } catch (Exception e) {
            System.err.println("[qTrace] revert to saved image failed: " + e.getMessage());
        } finally {
            logger.attach(data);
            drafts.begin(data);
        }
    }

    /** Copies a snapshot's content into the ImageData the viewer and the project entry hold. */
    private static void applyImage(ImageData<BufferedImage> target, ImageData<BufferedImage> source) {
        target.getHierarchy().setHierarchy(source.getHierarchy());
        var workflow = target.getHistoryWorkflow();
        workflow.clear();
        workflow.addSteps(source.getHistoryWorkflow().getSteps());
        if (source.getImageType() != null) target.setImageType(source.getImageType());
        if (source.getColorDeconvolutionStains() != null)
            target.setColorDeconvolutionStains(source.getColorDeconvolutionStains());
        source.getProperties().forEach(target::setProperty);
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
        refreshIntegrity();
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
                ActivityLog.add("qTrace already recording — " + steps + " step(s) captured before panel opened.");
            else if (attached)
                ActivityLog.add("qTrace recording — waiting for first action.");
        });
        refreshPushAvailability();
    }

    /**
     * Enables the "Upload" button whenever a .qtcert already exists on disk for the
     * currently open image — not only right after a fresh stamp in this session.
     * Scans every case_&lt;id&gt;/certs/*.qtcert under the export dir for one whose
     * subject.image_sha256 matches the open image, keeping the most recently issued.
     */
    private void refreshPushAvailability() {
        if (panel == null) return;
        QTracePlugin ep = QTracePluginManager.getEntitled();
        if (ep == null) return; // Upload button doesn't exist without Compliance
        try {
            var imageData = logger != null ? logger.getCurrentImageData() : null;
            String imageHash = logger != null ? logger.getImageHash() : null;
            if (imageData == null || imageHash == null) {
                panel.setPushEnabled(false);
                return;
            }
            String imageName = imageData.getServer().getMetadata().getName();
            String base = imageName.replaceAll("[^a-zA-Z0-9._-]", "_");
            Path exportDir  = QTraceConfig.get().outputExportDir();
            Path qtracePath = exportDir.resolve(base + ".qtrace");
            if (!Files.exists(qtracePath) || !Files.isDirectory(exportDir)) {
                panel.setPushEnabled(false);
                return;
            }

            Path bestCert = null;
            Instant bestTime = null;
            try (var caseDirs = Files.list(exportDir)) {
                for (Path caseDir : (Iterable<Path>) caseDirs
                        .filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("case_"))
                        ::iterator) {
                    Path certsDir = caseDir.resolve("certs");
                    if (!Files.isDirectory(certsDir)) continue;
                    try (var certFiles = Files.list(certsDir)) {
                        for (Path certFile : (Iterable<Path>) certFiles
                                .filter(p -> p.toString().endsWith(".qtcert"))::iterator) {
                            try {
                                JsonObject cert = JsonParser.parseString(Files.readString(certFile)).getAsJsonObject();
                                JsonObject subject = cert.has("subject") ? cert.getAsJsonObject("subject") : null;
                                String certImageHash = subject != null && subject.has("image_sha256")
                                    && !subject.get("image_sha256").isJsonNull()
                                    ? subject.get("image_sha256").getAsString() : null;
                                if (!imageHash.equals(certImageHash)) continue;
                                Instant t = cert.has("issued_at")
                                    ? Instant.parse(cert.get("issued_at").getAsString()) : Instant.EPOCH;
                                if (bestTime == null || t.isAfter(bestTime)) {
                                    bestTime = t;
                                    bestCert = certFile;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (bestCert != null && Files.exists(bestCert.getParent().getParent().resolve("chain.jsonl"))) {
                lastQtracePath = qtracePath;
                lastCertPath   = bestCert;
                // Cert may have been issued in a previous session (e.g. the day before) —
                // reconstruct the in-memory stamp from it so pushToWorkspace() has data to send.
                if (lastStamp == null || !imageHash.equals(lastStamp.imageHash())) {
                    lastStamp = buildStampFromCert(bestCert);
                }
                panel.setPushEnabled(true);
            } else {
                panel.setPushEnabled(false);
            }
        } catch (Exception e) {
            panel.setPushEnabled(false);
        }
    }

    /** Rebuilds a ValidationStamp from a .qtcert's embedded validation payload (cert issued in a prior session). */
    private ValidationStamp buildStampFromCert(Path certFile) {
        try {
            JsonObject cert = JsonParser.parseString(Files.readString(certFile)).getAsJsonObject();
            JsonObject payload = cert.getAsJsonObject("qtrace_payload");
            JsonObject v = payload.getAsJsonObject("validation");
            String statusLabel = str(v, "statusLabel", "1-In Progress");
            int statusIndex = 1;
            for (int i = 0; i < ValidationStamper.STATUS_LABELS.length; i++)
                if (ValidationStamper.STATUS_LABELS[i].equals(statusLabel)) { statusIndex = i; break; }
            return new ValidationStamp(
                str(v, "validator", null),
                Instant.parse(str(v, "timestamp", Instant.EPOCH.toString())),
                str(v, "scope", null),
                str(v, "confidence", null),
                str(v, "notes", ""),
                null, // gitHash — not embedded in the cert payload
                str(v, "imageHash", null),
                str(v, "qpdata_sha256", null),
                str(v, "case_id", cert.has("case_id") ? cert.get("case_id").getAsString() : null),
                str(v, "classifier_fidelity", null),
                statusIndex,
                statusLabel,
                str(v, "signature", null),
                str(v, "validatorKeyPub", null)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    public void showDashboard() {
        // Use Project Folder on + no project open: ask for a project rather than showing
        // whatever sits in the configured fallback folder.
        if (QTraceConfig.get().readExportDir().isEmpty()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle("qTrace — Dashboard");
            a.setHeaderText(QTraceI18n.t("dashboard.project.header"));
            a.setContentText(QTraceI18n.t("dashboard.project.content"));
            if (qupath.getStage() != null) a.initOwner(qupath.getStage());
            ButtonType open = new ButtonType(QTraceI18n.t("dashboard.project.open"), ButtonBar.ButtonData.OK_DONE);
            a.getButtonTypes().setAll(open, ButtonType.CANCEL);
            if (a.showAndWait().orElse(ButtonType.CANCEL) != open) return;
            qupath.getCommonActions().PROJECT_OPEN.handle(new javafx.event.ActionEvent());
            if (QTraceConfig.get().readExportDir().isEmpty()) return; // still no project
        }
        if (dashboard == null || !dashboard.isShowing()) {
            dashboard = new QTraceDashboard(qupath);
            dashboard.show();
        } else if (dashboard.isIconified()) {
            dashboard.show();
        } else {
            dashboard.minimize();
        }
    }

    /** Exports every .qtrace file's Dashboard data to a user-chosen CSV file. */
    public void exportDashboardCsv() {
        QTraceDashboard.runCsvExport(qupath);
    }

    /** Opens the commit-graph window for the current image's .qtrace (Compliance feature). */
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

    private final java.util.concurrent.atomic.AtomicInteger integrityGen = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Re-checks the open image's latest stamp (signature + .qpdata on disk) and shows the
     * panel's integrity alert when it is corrupted or the data changed since. Hashing runs
     * off the FX thread; a result for an image that has since been replaced is dropped.
     */
    public void refreshIntegrity() {
        if (panel == null) return;
        int gen = integrityGen.incrementAndGet();
        var imageData = logger != null ? logger.getCurrentImageData() : null;
        File qtrace = currentQtraceFile();
        if (imageData == null || qtrace == null) {
            panel.setIntegrity(StampIntegrity.State.NO_STAMP, null);
            return;
        }
        var project = qupath.getProject();
        ProjectImageEntry<?> entry = project != null ? project.getEntry(imageData) : null;
        Thread t = new Thread(() -> {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(qtrace.toPath())).getAsJsonObject();
                String sha = null;
                if (entry != null && entry.getEntryPath() != null) {
                    Path qpdata = entry.getEntryPath().resolve("data.qpdata");
                    if (Files.exists(qpdata)) sha = io.qtrace.chain.Hashing.sha256Hex(qpdata);
                }
                Path exportDir = qtrace.toPath().getParent();
                StampIntegrity.State state = StampIntegrity.check(root, sha,
                    StampIntegrity.findCertPayload(root, exportDir), exportDir,
                    QTraceConfig.get().outputTrainingDir());
                if (gen != integrityGen.get()) return;
                panel.setIntegrity(state, () -> ProvenanceDiffDialog.show(qupath.getStage(), root, qtrace, entry));
            } catch (Exception e) {
                System.err.println("[qTrace] refreshIntegrity: " + e.getMessage());
            }
        }, "qtrace-integrity");
        t.setDaemon(true);
        t.start();
    }

    /** Resolves the .qtrace path for the current image, or null if none yet. */
    private File currentQtraceFile() {
        try {
            var imageData = logger.getCurrentImageData();
            if (imageData == null) return null;
            String base = imageData.getServer().getMetadata().getName()
                .replaceAll("[^a-zA-Z0-9._-]", "_");
            Path outFile = QTraceConfig.get().outputExportDir().resolve(base + ".qtrace");
            return Files.exists(outFile) ? outFile.toFile() : null;
        } catch (Exception ignored) { return null; }
    }

    /** Opens the current image's .qtrace file with whatever the OS resolves for it, if one exists. */
    public void openCurrentQtraceFile() {
        File file = currentQtraceFile();
        if (file == null) {
            showGraphInfo(QTraceI18n.t("report.info.noqtrace"));
            return;
        }
        openWithOs(file);
    }

    /**
     * Launches the OS file-open command directly on the file. Deliberately avoids
     * java.awt.Desktop.open(File): on Linux, initializing AWT's Desktop inside a
     * JavaFX/GTK process can crash the JVM. xdg-open/open/cmd start don't touch AWT.
     */
    private static void openWithOs(File file) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win"))
                pb = new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath());
            else if (os.contains("mac"))
                pb = new ProcessBuilder("open", file.getAbsolutePath());
            else
                pb = new ProcessBuilder("xdg-open", file.getAbsolutePath());
            pb.start();
        } catch (Exception ignored) {}
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
        QTraceSettingsDialog.show(qupath);
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
                        ActivityLog.add("Script hook: Script Editor not open — reopen panel after opening it.");
                    return;
                }

                Object rawRunningTask = findField(se, "runningTask", Object.class);
                if (!(rawRunningTask instanceof javafx.beans.value.ObservableValue<?> runningTask)) {
                    if (panel != null)
                        ActivityLog.add("Script hook: runningTask not observable (type="
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
                                capturedOutput.forEach(line -> ActivityLog.add("  out> " + line));
                            }
                        });
                        pause.play();
                    }
                });

                scriptHookInstalled = true;
                if (panel != null)
                    ActivityLog.add("Script Editor hook installed — runs + output will be recorded.");

            } catch (Exception e) {
                ActivityLog.add("Script hook error: " + e.getMessage());
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
                ActivityLog.add("  [warn] Logback not found — output capture unavailable.");
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
            ActivityLog.add("  [warn] Logback appender failed: " + e.getMessage());
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

            if (path != null) ActivityLog.add("  path: " + path);

        } catch (Exception e) {
            ActivityLog.add("Script capture error: " + e.getMessage());
        }
    }

    // ── Replay (Compliance) ──────────────────────────────────────────────────

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
                Runnable switchImage = () -> {
                    if (logger != null) {
                        leaveImage();
                        attachImage(newData);
                    }
                    if (panel != null && panel.isShowing()) {
                        Platform.runLater(panel::refreshStatus);
                        refreshPushAvailability();
                        refreshIntegrity();
                    }
                };
                if (!maybePromptForgottenStamp(oldData, switchImage)) switchImage.run();
            }

            @Override public void visibleRegionChanged(QuPathViewer v, java.awt.Shape s) {}
            @Override public void selectedObjectChanged(QuPathViewer v, PathObject o)     {}
            @Override public void viewerClosed(QuPathViewer v) {
                leaveImage();
                drafts.end();
                if (logger != null) logger.detach();
                fireRecordingState(false);
            }
        });
    }

    /**
     * Warns when leaving an image that has captured actions but was never stamped —
     * users routinely forget to Stamp before switching images, right when QuPath's
     * own "save changes" prompt appears. Returns true if a (modal, async) reminder
     * was shown, in which case {@code proceed} is invoked once the user has answered;
     * returns false if there was nothing to warn about, so the caller should run
     * {@code proceed} immediately.
     */
    private boolean maybePromptForgottenStamp(ImageData<BufferedImage> oldData, Runnable proceed) {
        boolean hasSteps = logger != null && logger.hasNewSteps();
        String  imageHash = logger != null ? logger.getImageHash() : null;
        int     currentStepCount = logger != null ? logger.getCapturedSteps().size() : 0;
        boolean alreadyStamped = imageHash != null && lastStamp != null
            && imageHash.equals(lastStamp.imageHash())
            && currentStepCount <= lastStampStepCount;

        if (skipStampReminder || !QTraceConfig.get().isPromptUnstampedReminder()
            || oldData == null || logger == null || !hasSteps) return false;
        if (alreadyStamped) return false;

        String imageName = oldData.getServer().getMetadata().getName();
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.initOwner(qupath.getStage());
            alert.setTitle("qTrace — Unstamped image");
            alert.setHeaderText("\"" + imageName + "\" wasn't stamped");
            alert.setContentText(
                "You captured actions on this image but never validated & stamped it. "
              + "Stamp it now before continuing?");
            ButtonType btnStamp   = new ButtonType("Stamp now", ButtonBar.ButtonData.OK_DONE);
            ButtonType btnSkip    = new ButtonType(QTraceConfig.get().isAutosaveEnabled()
                ? "Continue — keep it as an unstamped session" : "Continue without stamping",
                ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType btnDontAsk = new ButtonType("Don't ask again this session", ButtonBar.ButtonData.OTHER);
            alert.getButtonTypes().setAll(btnStamp, btnSkip, btnDontAsk);

            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == btnStamp) {
                recordTrace();
            } else if (result.isPresent() && result.get() == btnDontAsk) {
                skipStampReminder = true;
            }
            proceed.run();
        });
        return true;
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
            ActivityLog.add("Nothing to record — no steps captured yet.");
            return;
        }
        // The stamp binds qpdata_sha256 to the file on disk: stamping unsaved work would
        // certify a stale .qpdata that doesn't contain what the validator is attesting.
        var imageData = logger.getCurrentImageData();
        if (imageData != null && imageData.isChanged()) {
            showSaveBeforeStampInfo();
            ActivityLog.add("Stamp cancelled — save your work on this image first (File › Save, Ctrl+S).");
            return;
        }
        logger.refreshAllAnnotationCaptures();
        ensureProjectFolderDirs();
        String currentStatus = readCurrentStatus();
        String qpdataHash = resolveQpdataHash();
        String defaultCaseId = resolveDefaultCaseId();
        ValidationStamper.show(qupath.getStage(), null, logger.getImageHash(), qpdataHash,
                               logger.computeClassifierFidelity(), currentStatus, defaultCaseId)
            .ifPresentOrElse(
                stamp -> {
                    lastStamp = stamp;
                    lastStampStepCount = logger.getCapturedSteps().size();
                    ActivityLog.add("Validation stamp recorded:");
                    ActivityLog.add("  validator  : " + stamp.validator());
                    ActivityLog.add("  scope      : " + stamp.scope());
                    ActivityLog.add("  confidence : " + stamp.confidence());
                    if (!stamp.notes().isEmpty())
                        ActivityLog.add("  notes      : " + stamp.notes());
                    if (panel != null) panel.setValidated(true, stamp.validator());
                    exportReport();
                    refreshIntegrity();
                },
                () -> { ActivityLog.add("Record cancelled."); }
            );
    }

    private void showSaveBeforeStampInfo() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(qupath.getStage());
        a.setTitle("qTrace — Save before stamping");
        a.setHeaderText("Your work on this image isn't saved yet");
        // The pixels are never modified: what gets saved (and stamped) is the work on top of
        // them — QuPath's .qpdata data file (objects, measurements, history).
        a.setContentText(
            "The stamp certifies your work on this image — annotations, detections, measurements "
          + "and history — as saved in its QuPath data file (.qpdata); the image pixels are never "
          + "modified. Save your work first (File › Save, or Ctrl+S), then click Stamp again.");
        a.showAndWait();
    }

    /** Creates the <project>/qTrace/{trace,geoJson,logs,gitTrack} subfolders on Stamp, when Project Folder mode is on. */
    private void ensureProjectFolderDirs() {
        QTraceConfig cfg = QTraceConfig.get();
        if (!cfg.isUseProjectFolder()) return;
        var project = qupath.getProject();
        if (project == null || project.getPath() == null) return;
        try {
            QTraceConfig.createProjectDirs(project.getPath().getParent());
        } catch (java.io.IOException e) {
            System.err.println("[qTrace] ensureProjectFolderDirs: " + e.getMessage());
        }
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
                    return io.qtrace.chain.Hashing.sha256Hex(qpdata);
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
            java.nio.file.Path outFile = QTraceConfig.get().outputExportDir().resolve(base + ".qtrace");
            if (!java.nio.file.Files.exists(outFile)) return null;
            JsonObject root = JsonParser.parseString(java.nio.file.Files.readString(outFile)).getAsJsonObject();
            return root.has("status") ? root.get("status").getAsString() : null;
        } catch (Exception ignored) { return null; }
    }

    public void exportReport() {
        if (logger == null || !logger.hasSteps()) {
            ActivityLog.add("Nothing to export — capture steps first.");
            return;
        }
        try {
            Path outDir  = QTraceConfig.get().outputExportDir();
            var exporter = new QTraceExporter(logger, null, lastStamp);
            exporter.setExtensions(collectLoadedExtensions());
            exporter.setSessionId(drafts.sessionId());
            Path outFile = exporter.export(outDir);
            drafts.markCommitted();
            Path csvFile = exporter.appendToMasterCsv(outDir);
            QTraceExporter.appendExternalFile(outFile, "csv", csvFile);

            ActivityLog.add(".qtrace written:");
            ActivityLog.add("  " + outFile.getFileName());
            ActivityLog.add("  CSV: " + csvFile.getFileName());

            lastThumbnailPath = null;
            try {
                var viewer = qupath.getViewer();
                if (viewer != null && viewer.getImageData() != null) {
                    var snapshot = GuiTools.makeViewerSnapshot(viewer);
                    String base = outFile.getFileName().toString().replaceAll("\\.qtrace$", "");
                    lastThumbnailPath = ThumbnailGenerator.generate(snapshot, outDir, base);
                    QTraceExporter.appendExternalFile(outFile, "thumbnail", lastThumbnailPath);
                    ActivityLog.add("  thumbnail: " + lastThumbnailPath.getFileName());
                }
            } catch (Exception e) {
                ActivityLog.add("  thumbnail: " + e.getMessage());
            }

            // Manual annotations GeoJSON — written by the exporter into outputTrainingDir(),
            // a separately configurable directory, not outDir. Resolve it here (rather than
            // in QTraceExporter) since it's read back from the .qtrace we just wrote anyway,
            // same as the certificate block below.
            lastGeojsonPath = null;
            try {
                JsonObject qtrootForGeo = JsonParser.parseString(Files.readString(outFile)).getAsJsonObject();
                JsonArray sessionsForGeo = qtrootForGeo.getAsJsonArray("sessions");
                JsonObject lastSessionForGeo = sessionsForGeo.get(sessionsForGeo.size() - 1).getAsJsonObject();
                JsonObject annObj = lastSessionForGeo.has("annotations") && lastSessionForGeo.get("annotations").isJsonObject()
                    ? lastSessionForGeo.getAsJsonObject("annotations") : null;
                String geoName = annObj != null && annObj.has("geojson_file")
                    ? annObj.get("geojson_file").getAsString() : null;
                if (geoName != null && !geoName.isBlank()) {
                    Path candidate = QTraceConfig.get().outputTrainingDir().resolve(geoName);
                    if (Files.exists(candidate)) lastGeojsonPath = candidate;
                }
            } catch (Exception ignored) {}

            // Compliance: build .qtcert chain-of-custody certificate (only when licensed & active)
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
                        QTraceExporter.appendExternalFile(outFile, "cert", certPath);
                        ActivityLog.add("  .qtcert: " + certPath.getFileName());
                        if (panel != null) panel.setPushEnabled(true);
                    }
                } catch (Exception e) {
                    ActivityLog.add("  .qtcert: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            ActivityLog.add("Export error: " + e.getMessage());
        }
    }

    // ── Import objects from file (menu hook) ──────────────────────────────────

    /**
     * Replaces the "File > Import objects from file" menu item's action with our own,
     * so we get the chosen File reference directly instead of only seeing the resulting
     * PathObjectHierarchyEvent (which carries no information about its source file).
     * Matched by text — QuPath exposes no stable id for this Action from extension code.
     * Called once at startup from QTraceExtension.installExtension().
     */
    public void hookImportObjectsMenuItem(QuPathGUI qupath) {
        var fileMenu = qupath.getMenu("File", false);
        if (fileMenu == null) return;
        javafx.scene.control.MenuItem item = findMenuItemByText(fileMenu.getItems(), "import objects from file");
        if (item == null) return;

        item.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Choose file to import");
            List<String> exts = PathIO.getObjectFileExtensions(true);
            if (!exts.isEmpty()) {
                String[] patterns = exts.stream().map(ext -> "*" + ext).toArray(String[]::new);
                chooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("QuPath objects", patterns));
            }
            File file = chooser.showOpenDialog(qupath.getStage());
            if (file == null) return;

            ImageData<BufferedImage> imageData = qupath.getImageData();
            if (imageData == null) return;

            int before = imageData.getHierarchy().getAllObjects(false).size();
            logger.setImportInProgress(true);
            boolean ok;
            try {
                ok = InteractiveObjectImporter.promptToImportObjectsFromFile(imageData, file);
            } finally {
                logger.setImportInProgress(false);
            }
            if (ok) {
                int after = imageData.getHierarchy().getAllObjects(false).size();
                logger.recordFileImport(file, Math.max(0, after - before));
                syncPanelState();
            }
        });
    }

    private javafx.scene.control.MenuItem findMenuItemByText(
            List<javafx.scene.control.MenuItem> items, String textLower) {
        for (javafx.scene.control.MenuItem item : items) {
            if (item.getText() != null && item.getText().toLowerCase().contains(textLower)) return item;
            if (item instanceof javafx.scene.control.Menu sub) {
                javafx.scene.control.MenuItem found = findMenuItemByText(sub.getItems(), textLower);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ── Cloud workspace push (Compliance) ────────────────────────────────────

    public void pushToWorkspace() {
        QTracePlugin ep = QTracePluginManager.getEntitled();
        if (ep == null) return;
        if (lastCertPath == null || lastQtracePath == null) {
            ActivityLog.add("☁ Nothing to push — export first.");
            return;
        }
        // chain.jsonl is at case_<id>/chain.jsonl, cert at case_<id>/certs/<id>.qtcert
        Path chainLog = lastCertPath.getParent().getParent().resolve("chain.jsonl");
        if (!chainLog.toFile().exists()) {
            ActivityLog.add("☁ chain.jsonl not found.");
            return;
        }
        java.util.Collection<ClassifierRecord> classifiers = logger.getKnownClassifiers().values();
        java.util.Collection<ImportedObjectFileRecord> importedFiles = logger.getImportedFiles().values();
        if (panel != null) {
            ActivityLog.add("☁ Pushing to workspace…");
            ActivityLog.add("  · " + lastQtracePath.getFileName());
            ActivityLog.add("  · " + lastCertPath.getFileName());
            ActivityLog.add("  · chain.jsonl");
            for (ClassifierRecord clf : classifiers)
                ActivityLog.add("  · classifiers/" + clf.name + ".json");
            for (ImportedObjectFileRecord imp : importedFiles)
                ActivityLog.add("  · imports/" + imp.companionFilename);
            if (lastThumbnailPath != null) ActivityLog.add("  · thumbnail.jpg");
            if (lastGeojsonPath != null) ActivityLog.add("  · " + lastGeojsonPath.getFileName());
            panel.setPushEnabled(false);
            panel.startPushProgress();
        }
        ep.pushToWorkspace(lastStamp, lastCertPath, chainLog, lastQtracePath, classifiers, lastThumbnailPath, importedFiles, lastGeojsonPath)
          .thenAccept(url -> {
              if (panel != null) panel.stopPushProgress();
              if (url != null && !url.startsWith("ERROR:")) {
                  ActivityLog.add("☁ " + url);
              } else {
                  if (panel != null) {
                      String detail = url != null ? url.substring("ERROR:".length()) : "network error";
                      ActivityLog.add("☁ Push failed: " + detail);
                      panel.setPushEnabled(true);
                  }
              }
          })
          .exceptionally(t -> {
              if (panel != null) {
                  panel.stopPushProgress();
                  ActivityLog.add("☁ Push error: " + t.getMessage());
                  panel.setPushEnabled(true);
              }
              return null;
          });
    }

    /** Generates an LLM activity report for the current image's .qtrace (Compliance feature). */
    public void generateActivityReport() {
        QTracePlugin ep = QTracePluginManager.getEntitled();
        if (ep == null) return;
        if (qupath.getProject() == null) {
            showGraphInfo(QTraceI18n.t("graph.info.noproject"));
            return;
        }
        if (qupath.getImageData() == null) {
            showGraphInfo(QTraceI18n.t("graph.info.noimage"));
            return;
        }
        File qtrace = currentQtraceFile();
        if (qtrace == null) {
            showGraphInfo(QTraceI18n.t("report.info.noqtrace"));
            return;
        }
        ActivityLog.add("▤ " + QTraceI18n.t("report.generating"));
        // Build the digest off the FX thread — a .qtrace can be tens of MB.
        CompletableFuture.supplyAsync(() -> ep.buildReportDigest(qtrace.toPath()))
            .thenAccept(digest -> Platform.runLater(() -> {
                if (digest == null || digest.isBlank()) {
                    ActivityLog.add("▤ " + QTraceI18n.t("report.failed"));
                    return;
                }
                String lang = QTraceConfig.get().getReportLanguage();
                // Security/transparency: let the user review exactly what is transmitted.
                if (QTraceConfig.get().isReportConfirmBeforeSend()) {
                    ReportConfirmDialog.Result r = ReportConfirmDialog.show(qupath.getStage(), digest);
                    if (!r.send) {
                        ActivityLog.add("▤ " + QTraceI18n.t("report.cancelled"));
                        return;
                    }
                    QTraceConfig cfg = QTraceConfig.get();
                    boolean changed = false;
                    if (r.dontAskAgain) { cfg.setReportConfirmBeforeSend(false); changed = true; }
                    if (r.lang != null) {
                        lang = r.lang;
                        if (!r.lang.equals(cfg.getReportLanguage())) { cfg.setReportLanguage(r.lang); changed = true; }
                    }
                    if (changed) cfg.save();
                }
                // Audit artifact: persist exactly what was sent, next to the .qtrace.
                writeReportAuditInput(qtrace.toPath(), digest);
                sendReportAndShow(ep, qtrace.toPath(), digest, lang);
            }))
            .exceptionally(t -> {
                Platform.runLater(() -> {
                    ActivityLog.add("▤ " + QTraceI18n.t("report.error") + ": " + t.getMessage());
                });
                return null;
            });
    }

    private void sendReportAndShow(QTracePlugin ep, Path qtrace, String digest, String lang) {
        ep.sendReportDigest(digest, lang)
          .thenAccept(markdown -> Platform.runLater(() -> {
              if (markdown == null || markdown.isBlank()) {
                  ActivityLog.add("▤ " + QTraceI18n.t("report.failed"));
              } else {
                  ActivityLog.add("▤ " + QTraceI18n.t("report.ready"));
                  ReportDialog.show(qupath.getStage(), qtrace, markdown, ep,
                      msg -> { ActivityLog.add("▤ " + msg); });
              }
          }))
          .exceptionally(t -> {
              Platform.runLater(() -> {
                  ActivityLog.add("▤ " + QTraceI18n.t("report.error") + ": " + t.getMessage());
              });
              return null;
          });
    }

    /** Writes the exact digest sent to Claude next to the .qtrace, as an audit trail. */
    private void writeReportAuditInput(Path qtrace, String digest) {
        try {
            String base = qtrace.getFileName().toString();
            if (base.endsWith(".qtrace")) base = base.substring(0, base.length() - ".qtrace".length());
            Path out = qtrace.resolveSibling(base + ".report-input.json");
            Files.write(out, digest.getBytes(StandardCharsets.UTF_8));
            ActivityLog.add("▤ " + QTraceI18n.t("report.audit.saved") + " " + out.getFileName());
        } catch (Exception e) {
            ActivityLog.add("▤ " + QTraceI18n.t("report.error") + ": " + e.getMessage());
        }
    }

    // ── Import .qTrace (Compliance stub) ─────────────────────────────────────

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
        return collectLoadedExtensions(qupath);
    }

    private static JsonArray collectLoadedExtensions(QuPathGUI qupath) {
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

        Path exportDir = QTraceConfig.get().outputExportDir();
        var  exporter  = new QTraceExporter(logger, null, lastStamp);
        exporter.setExtensions(collectLoadedExtensions());
        exporter.setSessionId(drafts.sessionId());
        Path out       = exporter.export(exportDir);
        drafts.markCommitted();
        Path csvFile   = exporter.appendToMasterCsv(exportDir);
        QTraceExporter.appendExternalFile(out, "csv", csvFile);
        return out.getFileName().toString();
    }

    /**
     * Writes the attached image's .qtrace session without a stamp — used by the Compliance
     * Player at the end of a replay on each target, so every target gets its own .qtrace
     * (steps + replayed_from) even when it's never stamped. Stamping later appends a
     * stamped session as usual.
     */
    public static Path exportUnstamped(QuPathGUI qupath, ActionLogger logger) throws IOException {
        // No master_validation_log.csv row: that log lists validations, and this isn't one.
        var exporter = new QTraceExporter(logger, null, null);
        exporter.setExtensions(collectLoadedExtensions(qupath));
        QTraceController c = active;
        boolean ours = c != null && c.logger == logger && c.drafts != null;
        if (ours) exporter.setSessionId(c.drafts.sessionId());
        Path out = exporter.export(QTraceConfig.get().outputExportDir());
        if (ours) c.drafts.markCommitted();   // not committed a second time on image switch
        return out;
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
            ActivityLog.add("openEntry error: " + e.getMessage());
        }
    }

    /**
     * Same as {@link #openEntry(ProjectImageEntry)}, but static — for callers (e.g. the
     * Compliance Player's multi-image batch replay) that have a {@link QuPathGUI} but no
     * {@link QTraceController} instance. Must be called on the JavaFX thread.
     */
    public static void openEntryOn(QuPathGUI qupath, ProjectImageEntry<?> entry) {
        try {
            Method m = findMethodWithParam(qupath.getClass(), "openImageEntry", ProjectImageEntry.class);
            if (m != null) m.invoke(qupath, entry);
        } catch (Exception ignored) {}
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    public void resetCapture() {
        if (logger == null) return;
        logger.resetCapture();
        drafts.discard();
        lastStamp = null;
        lastStampStepCount = -1;
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
