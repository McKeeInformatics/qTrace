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

package io.qtrace.player;

import io.qtrace.ActionLogger;
import io.qtrace.QTraceConfig;
import io.qtrace.QTraceController;
import io.qtrace.QTracePlugin;
import io.qtrace.QTracePluginManager;
import io.qtrace.ReplayStamp;
import io.qtrace.player.ext.QTraceExtensionHandler;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The UX-Re-Player: opens a .qtrace, shows its steps as a playlist (include checkbox /
 * Type / Title / Step), and replays them for real — step-by-step or continuously — via
 * {@link ReplayExecutor}. Drives {@link QTracePreflightDialog#check} for compatibility and
 * {@link ReplayLogWriter} for the signed execution log.
 *
 * Replaces {@link QTraceReplayDialog} as the entry point from
 * {@link PlayerExtension}; the full-script (Script Editor / CLI) path is
 * untouched for anyone who still wants it.
 */
class QTraceReplayPlayer {

    // ── Catppuccin Mocha — same palette as QTraceDashboard, same tone ───────────
    private static final String BG        = "#1e1e2e";
    private static final String SURFACE   = "#181825";
    private static final String BORDER    = "#313244";
    private static final String TEXT      = "#cdd6f4";
    private static final String TEXT_SUB  = "#a6adc8";
    private static final String MUTED     = "#6c7086";
    private static final String BLUE      = "#89b4fa";
    private static final String GREEN     = "#a6e3a1";
    private static final String GREEN_SOFT = "#28352c"; // active-target row background
    private static final String PEACH     = "#fab387";
    private static final String RED       = "#f38ba8";
    private static final String PACKET    = "#cba6f7";   // a workflow's packets, as in the Version window
    private static final String ROW_ALT   = "#20202e";
    private static final String GOLD      = "#d4a84b";
    private static final String GOLD_BG   = "#0e0c08";

    private final QuPathGUI qupath;
    private final ActionLogger logger;
    private Stage stage;

    TextField inputField; // package-private: screenshot harness sets it for a realistic capture
    private Label infoLabel;
    private ProgressIndicator loadingSpinner; // spins next to infoLabel while loadQtrace() downloads/parses in the background
    private VBox playlistBox;
    private final SimpleBooleanProperty runningProperty = new SimpleBooleanProperty(false); // true while a run is active — disables Instructions/Target checkboxes, drives the Console spinner
    private ProgressIndicator runningSpinner;
    private ScrollPane playlistScroll;
    private CheckBox selectAllCheckBox;
    // Unchecks the instructions recorded only in unstamped sessions (never validated by a stamp).
    private CheckBox validatedOnlyCheckBox;
    // Shown only for a certificate (qtc_… ID / share link) whose .qtrace holds several sessions:
    // unchecked = replay the certified session only, checked = the image's whole history.
    // A workflow (.qtflow) is played whole or one packet at a time — see WorkflowSource.
    // The packets of the workflow, drawn like the Version window's nodes: click, Ctrl+click or
    // Shift+click chooses which ones play, and the strip shows where the run is.
    private HBox packetStrip;
    private ScrollPane packetScroll;
    private final java.util.TreeSet<Integer> selectedPackets = new java.util.TreeSet<>();
    private int lastClickedPacket = -1;
    private List<String> packetTitles = List.of();
    private java.util.IdentityHashMap<ReplayStep, Integer> packetOfStep = new java.util.IdentityHashMap<>();
    private boolean workflowLoaded;
    // A record (.qtrace, certificate) shows its sessions on the same strip: one session is
    // replayed, or the whole history ("All") — see RecordSessions.
    private boolean sessionStrip;
    private List<Integer> sessionInstructionCounts = List.of();

    // ── Instructions columns — resizable by dragging the header separators; both the header
    // and every row's labels are bound to the same width property so they never drift apart ──
    private final javafx.beans.property.DoubleProperty typeColWidth   = new javafx.beans.property.SimpleDoubleProperty(90);
    private final javafx.beans.property.DoubleProperty titleColWidth  = new javafx.beans.property.SimpleDoubleProperty(260);
    private final javafx.beans.property.DoubleProperty stepColWidth   = new javafx.beans.property.SimpleDoubleProperty(55);
    private final javafx.beans.property.DoubleProperty statusColWidth = new javafx.beans.property.SimpleDoubleProperty(150);
    private final javafx.beans.property.DoubleProperty timerColWidth  = new javafx.beans.property.SimpleDoubleProperty(60);
    private Button playBtn, nextBtn, stampBtn, openLogsBtn;
    private TextArea logArea;
    private SplitPane splitPane;

    // ── Milestone info — macro-level progress, distinct from Console's per-step detail ──────
    private Label milestoneLabel;
    private String currentMilestoneText = "Idle";
    private int totalWorkUnits;     // rows.size() * number of target images for this Play click
    private int completedWorkUnits;

    // ── Target image(s) panel — same expand/collapse pattern as Pre-check, but starts
    // collapsed (there's no "reserve" concept here to justify auto-opening) ─────────────
    private VBox targetPanel;
    private Label targetChevron;
    private Label targetHeaderText;
    private VBox targetDetailBox;
    private VBox targetRowsBox;
    private ScrollPane targetScrollPane;
    private TextField targetFilterField;
    private boolean targetExpanded = false;
    private final ObservableList<TargetRow> targetRows = FXCollections.observableArrayList();
    private boolean targetFilterToChecked = false; // true while a batch runs — only checked targets stay visible
    private TargetRow activeTargetRow; // the target currently being replayed, highlighted green
    private String currentTargetLabel; // non-null only while a batch run is iterating targets
    private List<String> currentBatchTargetNames; // every checked target for this Play click, in play order
    private Integer currentBatchIndex; // 1-based position of currentTargetLabel within currentBatchTargetNames

    // ── Pre-check panel — always expandable, auto-open only when there's a reserve ─────
    private VBox precheckPanel;
    private Label precheckChevron;
    private Label precheckHeaderText;
    private VBox precheckDetailBox;
    private boolean precheckExpanded = false;
    private QTraceReplayEngine.PreflightInfo currentPreflightInfo;
    // Pixel classifiers referenced by the currently loaded .qtrace, and the outcome of
    // downloading them (see loadQtrace) — read by renderPrecheck() to build the
    // "Pixel Classifiers" row. classifierResult stays null only when there was nothing to
    // download (classifierNames empty); a download attempt that fails outright still
    // produces a DownloadResult with every name in failed(), never a null result.
    private List<String> currentClassifierNames = List.of();
    private QTraceReplayEngine.DownloadResult currentClassifierResult;
    // Temp dir holding this load's downloaded classifier JSONs (see loadQtrace) — deleted on
    // the next load and on window close, mirroring the CLI/Dialog replay paths' own cleanup.
    private java.nio.file.Path classifierTempDir;

    private ObservableList<PlaylistRow> rows = FXCollections.observableArrayList();
    private final Map<PlaylistRow, RowView> rowViews = new HashMap<>();
    private PlaylistRow highlighted;
    private ReplayExecutor executor;
    // Records each OK instruction as one step of the target's own .qtrace — see ReplayRecorder.
    // Bound to one opened image (recorderImage); rebound when the replay moves to another target.
    private ReplayRecorder recorder;
    private ImageData<BufferedImage> recorderImage;
    private Boolean pendingKeepHistory; // set by openTargetAndWait: did the target have saved data before we opened it?
    private volatile boolean traceExportPending; // an instruction ran on recorderImage since its last .qtrace export
    private String replaySource;        // what the user opened: local path, qtc_… ID or share link
    private ReplayLogWriter logWriter;
    private ReplayStamp pendingStamp;
    private String sourceQtraceHash;
    private String qtraceBaseName;
    // Directory the source .qtrace was loaded from — null for a remote/downloaded source.
    // Set on the executor's binding as _qtraceSourceDir so an "Import objects from file"
    // step's fragment can resolve its companion file by name only.
    private String qtraceSourceDir;
    private int cursor = 0;
    private boolean playing = false;
    // The loaded .qtrace and the session range it's replayed over — see applySessionScope().
    private String loadedQtraceJson;
    private int certifiedSession = -1;   // index of the session the certificate covers, -1 for a local .qtrace
    private int sessionCount = 0;
    private boolean preambleRun = false;

    // ── Shared with the mini-player (QTraceMiniPlayerBar) ──────────────────
    // The bar drives this same instance (load, ▶, ▶│, targets) and renders status(); the
    // window is just the detailed view of it (⤢), built even when never shown.
    private final List<Runnable> stateListeners = new ArrayList<>();
    private volatile MiniPlayerStatus.Phase phase = MiniPlayerStatus.Phase.EMPTY;
    private volatile String phaseError;
    private volatile PlaylistRow runningRow;  // the row executing now, or the last one executed
    private int runApplied, runFailed, runSkipped, runTraces; // over one whole Play / step-through
    private int lastImageCount = 1;           // images of the run that just finished, for DONE
    private QTracePreflightDialog.CompatResult currentCompat;
    private boolean keepAliveOnClose;         // the bar owns this Player: closing the window only hides it
    private Runnable onDisposed;
    private Button toMiniBtn;                 // ⤡ — back to the mini-player; shown only when there is one
    // The batch a pause (or a failure pause) interrupted — ▶ resumes it where it stopped instead
    // of starting over, since an instruction already applied in QuPath can't be undone.
    private List<TargetRow> activeBatch;
    private int activeBatchIndex;

    QTraceReplayPlayer(QuPathGUI qupath, ActionLogger logger) {
        this.qupath = qupath;
        this.logger = logger;
    }

    /** Shows the Player window — building it first if the mini-player hasn't already. */
    void show() {
        ensureBuilt();
        stage.show();
        stage.toFront();
    }

    boolean isWindowShowing() { return stage != null && stage.isShowing(); }

    /**
     * Builds the window without showing it: every piece of the replay (playlist rows, log,
     * transport state) lives in these nodes, so the mini-player needs them to exist too.
     */
    void ensureBuilt() {
        if (stage != null) return;
        stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle("qTrace Player");
        stage.setMinWidth(560);
        stage.setMinHeight(480);
        stage.setWidth(900);
        stage.setHeight(820); // same default height as QTraceDashboard

        // ── Input row ────────────────────────────────────────────────────────
        // Text fallback, not emoji-only: on some Linux/JavaFX setups the "📂" glyph doesn't
        // exist in the resolved system font and silently renders nothing — an emoji-only
        // button then looks like an empty, invisible button instead of failing loudly.
        Button browseBtn = new Button("📂 Browse");
        browseBtn.setId("browse-button"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        browseBtn.setFont(Font.font("System", 12));
        browseBtn.setTextFill(Color.web(TEXT_SUB));
        browseBtn.setStyle(
            "-fx-background-color: " + SURFACE + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 5 9 5 9;");
        Tooltip.install(browseBtn, new Tooltip("Open a local .qtrace file"));
        browseBtn.setOnAction(e -> browseLocalFile());

        inputField = new TextField();
        inputField.setPromptText("Local .qtrace path, qtc_… ID, or share link");
        inputField.setStyle(fieldStyle());
        HBox.setHgrow(inputField, Priority.ALWAYS);

        Button openBtn = new Button("Open");
        openBtn.setId("open-button"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        styleButton(openBtn, BLUE);
        openBtn.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
            () -> inputField.getText().isBlank(), inputField.textProperty()));
        openBtn.setOnAction(e -> loadQtrace(inputField.getText().strip()));
        inputField.setOnAction(e -> openBtn.fire());

        // Mirror of the mini-player's ⤢: same replay, just the compact view.
        toMiniBtn = new Button("⤡");
        toMiniBtn.setId("to-mini-button");
        toMiniBtn.setFont(Font.font("System", 13));
        toMiniBtn.setTextFill(Color.web(TEXT_SUB));
        toMiniBtn.setStyle(
            "-fx-background-color: " + SURFACE + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 4 9 4 9;");
        Tooltip.install(toMiniBtn, new Tooltip("Switch to the mini-player"));
        toMiniBtn.setOnAction(e -> stage.hide());
        toMiniBtn.setVisible(keepAliveOnClose);
        toMiniBtn.setManaged(keepAliveOnClose);

        HBox inputRow = new HBox(8, browseBtn);
        // One button per module source (e.g. "Training…"), right after Browse.
        for (io.qtrace.PlayerSource source : moduleSources()) {
            Button sourceBtn = new Button(source.label());
            sourceBtn.setFont(browseBtn.getFont());
            sourceBtn.setTextFill(Color.web(TEXT_SUB));
            sourceBtn.setStyle(browseBtn.getStyle());
            sourceBtn.setOnAction(e -> chooseFromSource(source));
            inputRow.getChildren().add(sourceBtn);
        }
        inputRow.getChildren().addAll(inputField, openBtn, toMiniBtn);
        inputRow.setAlignment(Pos.CENTER_LEFT);

        infoLabel = label("No replay loaded.", MUTED, 11, FontWeight.NORMAL);
        loadingSpinner = new ProgressIndicator();
        loadingSpinner.setMaxSize(12, 12);
        loadingSpinner.setPrefSize(12, 12);
        loadingSpinner.setStyle("-fx-progress-color: " + BLUE + ";");
        loadingSpinner.setVisible(false);
        loadingSpinner.setManaged(false);
        HBox infoRow = new HBox(6, loadingSpinner, infoLabel);
        infoRow.setAlignment(Pos.CENTER_LEFT);

        // ── Target image(s) ─────────────────────────────────────────────────
        targetPanel = buildTargetImagesPanel();
        refreshTargetImages();

        // ── Pre-check ────────────────────────────────────────────────────────
        // Not added to splitPane yet — starts absent (no .qtrace loaded), inserted by
        // setPrecheckVisible(true) once loadQtrace() has something to check.
        precheckPanel = buildPrecheckPanel();

        // ── Instructions (playlist) ─────────────────────────────────────────
        StackPane playlistSection = buildPlaylistSection();

        // ── Transport — sits below Instructions, above Console ─────────────
        playBtn  = transportButton("▶", e -> togglePlay());
        nextBtn  = transportButton("▶│", e -> stepForward());

        HBox transportGroup = new HBox(6, playBtn, nextBtn);
        transportGroup.setId("transport-group"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        transportGroup.setAlignment(Pos.CENTER);
        HBox.setHgrow(transportGroup, Priority.ALWAYS);

        stampBtn = new Button("🏅 Stamp");
        stampBtn.setId("stamp-button"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        stampBtn.setFont(Font.font("Monospaced", FontWeight.BOLD, 10));
        stampBtn.setDisable(true);
        applyGrayStamp();
        stampBtn.setOnAction(e -> onStamp());

        Button exportCodeBtn = new Button("</> Export Code");
        exportCodeBtn.setFont(Font.font("System", 10));
        exportCodeBtn.setTextFill(Color.web(MUTED));
        exportCodeBtn.setStyle(
            "-fx-background-color: " + BG + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
        exportCodeBtn.setOnAction(e -> exportCode());

        HBox transportRow = new HBox(8, transportGroup, exportCodeBtn, stampBtn);
        transportRow.setAlignment(Pos.CENTER);
        transportRow.setPadding(new Insets(8, 0, 0, 0));
        setTransportEnabled(false);

        // ── Console — draggable split so it can be enlarged, same idea as the
        // dashboard panel's own resize grip, just scoped to this one region instead of the
        // whole window. ──────────────────────────────────────────────────────────────────
        Label logHeader = headerLabel("Activity log", -1);

        logArea = new TextArea();
        logArea.setId("activity-log"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        logArea.setEditable(false);
        logArea.setStyle(
            "-fx-control-inner-background: " + SURFACE + ";"
          + "-fx-text-fill: " + MUTED + ";"
          + "-fx-font-family: monospace;"
          + "-fx-font-size: 10;"
        );
        VBox.setVgrow(logArea, Priority.ALWAYS);

        // Icon-only — grey until a log file actually exists for the current run, green once
        // startNewRun() has one open (see setLogsButtonState).
        openLogsBtn = new Button();
        openLogsBtn.setStyle(
            "-fx-background-color: " + BG + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 4 8 4 8;");
        Tooltip.install(openLogsBtn, new Tooltip("Open the current run's log file (or the logs folder if none yet)"));
        openLogsBtn.setOnAction(e -> openLogsFolder());
        setLogsButtonState(false);

        // "Is it working" indicator — used to live over Instructions as a full overlay; now a
        // small spinner here instead, so Instructions itself stays visible and updates live.
        runningSpinner = new ProgressIndicator();
        runningSpinner.setMaxSize(14, 14);
        runningSpinner.setPrefSize(14, 14);
        runningSpinner.setStyle("-fx-progress-color: " + BLUE + ";");
        runningSpinner.setVisible(false);
        runningSpinner.setManaged(false);

        // Milestone info — macro-level progress ("Opening image X", "N%"), distinct from the
        // step-by-step technical detail in the Console above it.
        milestoneLabel = new Label();
        milestoneLabel.setFont(Font.font("System", 10));
        milestoneLabel.setTextFill(Color.web(MUTED));
        HBox.setHgrow(milestoneLabel, Priority.ALWAYS);
        resetMilestone();

        HBox logFooter = new HBox(8, runningSpinner, milestoneLabel, openLogsBtn);
        logFooter.setAlignment(Pos.CENTER_LEFT);
        logFooter.setPadding(new Insets(5, 0, 0, 0));

        VBox logSection = new VBox(4, logHeader, logArea, logFooter);
        logSection.setPadding(new Insets(6, 0, 0, 0));
        VBox.setVgrow(logSection, Priority.ALWAYS);

        // Below Instructions: transport bar, then the resizable console — the console grows
        // to fill whatever vertical space the window has, instead of leaving a blank strip
        // under it when the window is taller than the content.
        VBox bottomPane = new VBox(transportRow, logSection);
        VBox.setVgrow(bottomPane, Priority.ALWAYS);

        // Target's header is a splitPane item too (always present, height-capped to its own
        // content — see buildTargetImagesPanel), not a fixed VBox sibling before splitPane.
        // That's deliberate: Pre-check's header/detail and Target's own detail are ALSO
        // splitPane items, inserted/removed at specific indices (applyTargetExpanded,
        // setPrecheckVisible, applyPrecheckExpanded) — if the headers lived outside splitPane
        // while their details lived inside it, both headers would always render as one block
        // before both details as another block, regardless of which one's header they actually
        // belong to. Keeping every piece (header or detail, Target or Pre-check) as an item of
        // the SAME splitPane is what lets each detail land immediately under its own header.
        // Pre-check is inserted at index 0 when visible (see setPrecheckVisible), so it lands
        // above Target — the config check is meant to be seen before picking target images.
        splitPane = new SplitPane(targetPanel, playlistSection, bottomPane);
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        // Two dividers now that Target's (height-capped) header is itself an item — its own
        // maxHeight cap overrides this initial guess immediately, so the exact first value
        // barely matters; the second mirrors the previous single-divider default.
        splitPane.setDividerPositions(0.08, 0.65);
        splitPane.setStyle("-fx-background-color: " + BG + ";");
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        VBox root = new VBox(10, inputRow, infoRow, splitPane);
        root.setPadding(new Insets(14));
        root.setStyle("-fx-background-color: " + BG + ";");

        Scene scene = new Scene(root);
        // SplitPane's default divider is a light grey bar — a real stylesheet rule (rather than
        // restyling individual divider nodes after the fact via lookupAll) applies to every
        // divider automatically, including ones created later when Target/Pre-check's detail
        // panes get inserted — those divider nodes don't reliably exist yet at the single
        // Platform.runLater pulse right after insertion, which left some of them stuck on the
        // default light-grey skin.
        installDarkSplitDividerCss(scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (keepAliveOnClose) return; // the mini-player still uses this replay — just hide
            dispose();
        });
    }

    /** Releases the run's executor, log file and downloaded classifiers — once nothing uses this Player. */
    void dispose() {
        if (onDisposed != null) onDisposed.run();
        if (executor != null) executor.close();
        if (logWriter != null) { try { logWriter.close(); } catch (Exception ignored) {} }
        deleteClassifierTempDir();
        if (stage != null) stage.close();
    }

    // ── Mini-player API ──────────────────────────────────────────────────────

    /** Owned by the mini-player: closing the window only hides it, and ⤡ goes back to the pill. */
    /** Run once when this Player is released — lets the mini-player forget it. */
    void setOnDisposed(Runnable r) { onDisposed = r; }

    void setKeepAliveOnClose(boolean keep) {
        keepAliveOnClose = keep;
        if (toMiniBtn != null) { toMiniBtn.setVisible(keep); toMiniBtn.setManaged(keep); }
    }

    void addStateListener(Runnable listener) { stateListeners.add(listener); }

    private void fireState() {
        Platform.runLater(() -> stateListeners.forEach(Runnable::run));
    }

    private void setPhase(MiniPlayerStatus.Phase p, String error) {
        phase = p;
        phaseError = error;
        fireState();
    }

    boolean isLoaded()  { return !rows.isEmpty(); }
    boolean isPlaying() { return playing; }
    boolean isBusy()    { return runningProperty.get(); }
    boolean isFinished() { return !rows.isEmpty() && cursor >= rows.size(); }

    /** Snapshot for the mini-player's status line. FX thread. */
    MiniPlayerStatus status() {
        PlaylistRow row = runningRow;
        int step = row != null ? rows.indexOf(row) + 1 : Math.min(cursor, rows.size());
        int imageCount = phase == MiniPlayerStatus.Phase.DONE ? lastImageCount
            : currentBatchTargetNames != null ? currentBatchTargetNames.size() : 1;
        int imageIndex = currentBatchIndex != null ? currentBatchIndex : 1;
        return new MiniPlayerStatus(phase, qtraceBaseName, rows.size(), step,
            row != null ? packetPrefix(row) + row.step.title() : null, imageIndex, imageCount, phaseError,
            runApplied, runFailed, runSkipped, runTraces);
    }

    /** "packet 2/3 · " before the instruction's title in the mini-player, for a workflow's instruction. */
    private String packetPrefix(PlaylistRow row) {
        return row.packet >= 0 ? "packet " + (row.packet + 1) + "/" + packetTitles.size() + " · " : "";
    }

    /** Overall compatibility of the loaded replay, or null before one is loaded. */
    io.qtrace.player.ext.Compat compatOverall() {
        return currentCompat != null ? currentCompat.overall() : null;
    }

    /** Pre-check findings, one per line — what the mini-player's compat dot shows on click. */
    String precheckSummary() {
        if (currentCompat == null) return "No replay loaded.";
        StringBuilder sb = new StringBuilder("Compatibility: " + currentCompat.overall().label);
        for (var ext : currentCompat.extensions()) {
            sb.append('\n').append(ext.present() ? "✓ " : "✗ ").append(ext.name())
              .append(" — ").append(ext.compat().label);
            if (!ext.present()) sb.append(" · not installed");
            if (ext.caveat() != null && !ext.caveat().isBlank()) sb.append(" · ").append(ext.caveat());
        }
        currentCompat.modelStatuses().forEach((model, st) ->
            sb.append('\n').append("Model ").append(model).append(" — ").append(st));
        if (currentCompat.extensions().isEmpty() && currentCompat.modelStatuses().isEmpty())
            sb.append("\nNo extension or model required.");
        return sb.toString();
    }

    /** Opens the file chooser — the mini-player's "Local file…" entry. */
    void browseFromMiniPlayer() { browseLocalFile(); }

    /**
     * Sources offered by the modules this licence includes (e.g. the training catalogue), for the
     * Open entries of the Player and of the mini-player. Empty on a Core without PlayerSources.
     */
    static List<io.qtrace.PlayerSource> moduleSources() {
        try {
            return io.qtrace.PlayerSources.entitled();
        } catch (LinkageError e) {
            return List.of();
        }
    }

    /** Lets a module's source pick a record, then opens it like a pasted ID. */
    void chooseFromSource(io.qtrace.PlayerSource source) {
        try {
            source.choose(isWindowShowing() ? stage : qupath.getStage(), this::openFromMiniPlayer);
        } catch (RuntimeException e) {
            // A module's catalogue must never take the player down with it.
            infoLabel.setText("✗ " + source.label() + " is unavailable: " + e.getMessage());
        }
    }

    /** Loads a qtc_… ID, share link or path — the mini-player's "ID or link…" entry. */
    void openFromMiniPlayer(String source) {
        inputField.setText(source);
        loadQtrace(source.strip());
    }

    /**
     * The mini-player's target choice: only the open image, or every image of the project.
     * Ignored mid-run — a paused batch keeps the targets it started with.
     */
    void useTargets(boolean allImages) {
        if (playing || activeBatch != null) return;
        refreshTargetImages(); // pre-selects the open image only
        if (allImages) for (TargetRow row : targetRows) row.selected().set(true);
    }

    int projectImageCount() {
        Project<BufferedImage> project = qupath.getProject();
        return project != null ? project.getImageList().size() : 0;
    }

    void playFromMiniPlayer() { togglePlay(); }

    void stepFromMiniPlayer() { stepForward(); }

    /**
     * Expands the pre-check panel regardless of whether every check passed — normally it
     * only auto-opens when a check fails. Used by the screenshot harness so the panel's
     * detail (file hash, extensions, models, QuPath version) is visible even against a
     * fixture that happens to pass every check.
     */
    void expandPrecheck() {
        precheckExpanded = true;
        applyPrecheckExpanded();
    }

    /**
     * Re-asserts the window's target size. Loading a replay and expanding pre-check
     * (above) trigger relayouts (SplitPane divider/content changes) that were observed to
     * shrink the actual window well below the 900x820 set at construction, on at least one
     * environment — setMinWidth/setMinHeight stuck reliably where a plain setWidth/setHeight
     * follow-up didn't (same root cause as the Version Graph 200x200 bug). Screenshot-harness
     * only; real usage never calls this; the min the constructor sets (560x480) is what a
     * user can still resize down to normally.
     */
    void resnapSize() {
        stage.setMinWidth(900);
        stage.setMinHeight(820);
        stage.setWidth(900);
        stage.setHeight(820);
    }

    private void browseLocalFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open .qtrace or .qtflow file");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Records and workflows (.qtrace, .qtflow)", "*.qtrace", "*.qtflow"));
        // Remembers where the user last browsed from — falls back to the configured export
        // directory (Settings) the very first time, same default other file dialogs already use.
        java.io.File initialDir = QTraceConfig.get().getLastReplayBrowseDir().toFile();
        if (initialDir.isDirectory()) chooser.setInitialDirectory(initialDir);

        // From the mini-player the window is hidden — anchor the chooser on QuPath instead.
        java.io.File file = chooser.showOpenDialog(isWindowShowing() ? stage : qupath.getStage());
        if (file != null) {
            QTraceConfig cfg = QTraceConfig.get();
            cfg.setLastReplayBrowseDir(file.getParent());
            cfg.save();
            inputField.setText(file.getAbsolutePath());
            loadQtrace(file.getAbsolutePath());
        }
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    // Package-private (not private) so the screenshot harness — same io.qtrace package,
    // see PlayerScreenshotHarness — can load a fixture without going through the UI.
    void loadQtrace(String source) {
        if (source.isBlank()) return;
        setPhase(MiniPlayerStatus.Phase.LOADING, null);
        infoLabel.setText("Downloading…");
        loadingSpinner.setVisible(true);
        loadingSpinner.setManaged(true);
        rows.clear();

        new Thread(() -> {
            // Holds the temp dir created below across the try/catch, so a failure downstream
            // of the classifier download (e.g. buildPreambleScript throwing) still gets it
            // cleaned up instead of leaking — nothing in the Platform.runLater success path
            // below runs to adopt it in that case.
            java.nio.file.Path[] leakGuard = new java.nio.file.Path[1];
            try {
                String qtraceJson = QTraceReplayEngine.downloadQtrace(source);
                sourceQtraceHash = io.qtrace.chain.Hashing.sha256Hex(qtraceJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                qtraceBaseName = baseNameOf(source);
                replaySource = source;
                java.io.File sourceAsFile = new java.io.File(source);
                qtraceSourceDir = sourceAsFile.isFile() ? sourceAsFile.getParent() : null;

                QTraceReplayEngine.PreflightInfo info = QTraceReplayEngine.extractPreflightInfo(qtraceJson);
                QTracePreflightDialog.CompatResult compat = QTracePreflightDialog.check(qupath, info);

                // Download referenced pixel classifiers (if any) before building the preamble,
                // so restoration blocks — and the "Pixel Classifiers" pre-check row — reflect
                // what actually landed on disk rather than being silently skipped.
                List<String> classifierNames = QTraceReplayEngine.extractClassifierNames(qtraceJson);
                java.nio.file.Path newClassifierDir = null;
                QTraceReplayEngine.DownloadResult classifierResult = null;
                if (!classifierNames.isEmpty()) {
                    try {
                        String certId = QTraceReplayEngine.extractCertId(source);
                        newClassifierDir = java.nio.file.Files.createTempDirectory("qtrace_player_classifiers_");
                        leakGuard[0] = newClassifierDir;
                        // A workflow keeps its classifiers in a folder beside it; a certificate's are downloaded.
                        // (A workflow opened by its ID is downloaded like a certificate's.)
                        classifierResult = WorkflowSource.isWorkflow(qtraceJson) && RecordSource.parse(source).local()
                            ? WorkflowSource.copyClassifiers(source, classifierNames, newClassifierDir)
                            : QTraceReplayEngine.downloadClassifiers(certId, newClassifierDir);
                    } catch (Exception dlEx) {
                        Map<String, String> allFailed = new HashMap<>();
                        for (String name : classifierNames)
                            allFailed.put(name, dlEx.getMessage() != null ? dlEx.getMessage() : dlEx.getClass().getSimpleName());
                        classifierResult = new QTraceReplayEngine.DownloadResult(List.of(), allFailed);
                    }
                }
                java.nio.file.Path classifierDir = newClassifierDir;

                // A certificate covers one stamped session, not the image's whole history (earlier
                // sessions, unstamped work) that its .qtrace carries — replay that session by
                // default, like the portal's "Scripts executed". A local .qtrace replays everything.
                int certIdx = QTraceReplayEngine.isCertificateSource(source)
                    ? QTraceReplayEngine.certifiedSessionIndex(qtraceJson) : -1;
                int nSessions = countSessions(qtraceJson);
                // A training step names the packet of the workflow to play: the playlist opens on it.
                int askedPacket = WorkflowSource.isWorkflow(qtraceJson)
                    ? WorkflowSource.packetIndex(qtraceJson, WorkflowSource.packetOf(source)) : -1;
                boolean workflow = WorkflowSource.isWorkflow(qtraceJson);
                List<String> packets = workflow ? WorkflowSource.packetTitles(qtraceJson)
                    : RecordSessions.titles(qtraceJson, certIdx);
                List<Integer> sessionCounts = workflow ? List.of() : RecordSessions.instructionCounts(qtraceJson);
                // A workflow opens with all its packets chosen — or the one a training step names.
                // A certificate opens on the session it covers; a local .qtrace on its whole history.
                List<Integer> chosen = new ArrayList<>();
                if (askedPacket >= 0) chosen.add(askedPacket);
                else if (!workflow && certIdx >= 0) chosen.add(certIdx);
                else for (int i = 0; i < packets.size(); i++) chosen.add(i);
                WorkflowPlaylist workflowPlaylist = workflow ? workflowPlaylist(qtraceJson, chosen) : null;
                int scope = chosen.size() == 1 ? chosen.get(0) : -1;
                List<ReplayStep> steps = workflow ? workflowPlaylist.steps()
                    : listedSteps(QTraceReplayEngine.extractSteps(qtraceJson, scope));
                // A workflow holds only what is replayed: no display-only rows.
                List<ReplayStep> measurementMapSteps = workflow ? List.of()
                    : QTraceReplayEngine.extractMeasurementMapSteps(qtraceJson, scope);
                String preamble = QTraceReplayEngine.buildPreambleScript(qtraceJson, classifierDir, scope);

                java.nio.file.Path finalClassifierDir = newClassifierDir;
                QTraceReplayEngine.DownloadResult finalClassifierResult = classifierResult;
                Platform.runLater(() -> {
                    deleteClassifierTempDir(); // drop the previous load's temp dir before adopting this one
                    classifierTempDir = finalClassifierDir;
                    currentClassifierNames = classifierNames;
                    currentClassifierResult = finalClassifierResult;
                    loadingSpinner.setVisible(false);
                    loadingSpinner.setManaged(false);
                    this.loadedQtraceJson = qtraceJson;
                    this.certifiedSession = certIdx;
                    this.sessionCount = nSessions;
                    this.workflowLoaded = workflow;
                    this.sessionStrip = !workflow && !packets.isEmpty();
                    this.sessionInstructionCounts = sessionCounts;
                    packetTitles = packets;
                    selectedPackets.clear();
                    selectedPackets.addAll(chosen);
                    lastClickedPacket = chosen.size() == 1 ? chosen.get(0) : -1;
                    packetOfStep = workflow ? workflowPlaylist.packetOf() : new java.util.IdentityHashMap<>();
                    packetScroll.setVisible(workflow || sessionStrip);
                    packetScroll.setManaged(workflow || sessionStrip);
                    // Nothing in a workflow is stamped: "validated only" would uncheck everything.
                    validatedOnlyCheckBox.setVisible(!workflow);
                    validatedOnlyCheckBox.setManaged(!workflow);
                    populateRows(steps, measurementMapSteps, compat);
                    infoLabel.setText(infoText(info, steps.size(), compat, scope));
                    currentPreflightInfo = info;
                    precheckExpanded = false; // reset — renderPrecheck() re-opens it if there's a reserve
                    renderPrecheck(info, compat);
                    setPrecheckVisible(true);
                    setTransportEnabled(!steps.isEmpty());
                    if (logWriter != null) { try { logWriter.close(); } catch (Exception ignored) {} }
                    this.logWriter = null;
                    this.executor = null; // rebuilt by startNewRun() on first Play/step
                    this.preambleRunPreambleText = preamble;
                    this.currentCompat = compat;
                    this.runningRow = null;
                    this.activeBatch = null;
                    this.runApplied = this.runFailed = this.runSkipped = this.runTraces = 0;
                    setPhase(MiniPlayerStatus.Phase.READY, null);
                    this.recorder = null;
                    this.recorderImage = null;
                    this.pendingKeepHistory = null;
                    this.traceExportPending = false;
                    this.pendingStamp = null;
                    this.currentTargetLabel = null;
                    this.currentBatchTargetNames = null;
                    this.currentBatchIndex = null;
                    preambleRun = false;
                    cursor = 0;
                    refreshPacketStrip();
                    stampBtn.setDisable(true);
                    applyGrayStamp();
                    logArea.clear();
                    resetMilestone();
                    setLogsButtonState(false);
                    targetFilterToChecked = false;
                    activeTargetRow = null;
                    rebuildTargetNodes();
                });
            } catch (Exception ex) {
                if (leakGuard[0] != null) deleteTempDir(leakGuard[0]);
                Platform.runLater(() -> {
                    loadingSpinner.setVisible(false);
                    loadingSpinner.setManaged(false);
                    infoLabel.setText("✗ " + ex.getMessage());
                    setPrecheckVisible(false);
                    setPhase(MiniPlayerStatus.Phase.LOAD_ERROR, ex.getMessage());
                });
            }
        }, "qtrace-player-load").start();
    }

    /** Deletes {@code dir} and its contents, best-effort — same pattern as QTraceReplayDialog's cleanup. */
    private static void deleteTempDir(java.nio.file.Path dir) {
        try {
            java.nio.file.Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (java.io.IOException ignored) {} });
        } catch (java.io.IOException ignored) {}
    }

    /** Deletes {@link #classifierTempDir} (if any) and clears the field — called before adopting
     * a new load's temp dir, and on window close. */
    private void deleteClassifierTempDir() {
        if (classifierTempDir != null) {
            deleteTempDir(classifierTempDir);
            classifierTempDir = null;
        }
    }

    // stored separately from the field above since it's produced on a background thread
    private String preambleRunPreambleText;

    /**
     * Instructions the author took out of the replay are never run. By default they are not
     * listed either; with the setting off they stay, greyed out, to check what was left out.
     */
    private static List<ReplayStep> listedSteps(List<ReplayStep> steps) {
        return QTraceConfig.get().isHideSkippedReplaySteps() ? QTraceReplayEngine.withoutSkipped(steps) : steps;
    }

    private void populateRows(List<ReplayStep> steps, List<ReplayStep> measurementMapSteps,
            QTracePreflightDialog.CompatResult compat) {
        List<String> missingNames = compat.extensions().stream()
            .filter(c -> !c.present())
            .map(c -> c.name().toLowerCase())
            .collect(Collectors.toList());

        List<PlaylistRow> built = steps.stream().map(step -> {
            boolean incompatible = missingNames.stream().anyMatch(name ->
                step.groovyFragment().toLowerCase().contains(name)
                || step.title().toLowerCase().contains(name));
            return new PlaylistRow(step, incompatible);
        }).collect(Collectors.toList());
        // Measurement-map views: display-only, no script_fragment to run — appended after the
        // scriptable steps (see QTraceReplayEngine#extractMeasurementMapSteps), never flagged
        // incompatible (there's nothing extension-dependent about them).
        for (ReplayStep mmStep : measurementMapSteps)
            built.add(new PlaylistRow(mmStep, false));

        for (PlaylistRow row : built) row.packet = packetOfStep.getOrDefault(row.step, -1);
        rows.setAll(built);
        selectAllCheckBox.setSelected(true);
        validatedOnlyCheckBox.setSelected(false);
        highlighted = null;
        rebuildPlaylistNodes();
    }

    /**
     * What is replayed changed — a workflow's packets, or a record's session against its whole
     * history. Rebuilds the playlist and preamble, and rewinds — the next Play starts a fresh
     * run over the new list.
     */
    private void applySessionScope() {
        if (loadedQtraceJson == null || currentCompat == null) return;
        int scope = currentScope();
        WorkflowPlaylist workflowPlaylist = workflowLoaded ? workflowPlaylist(loadedQtraceJson, new ArrayList<>(selectedPackets)) : null;
        if (workflowLoaded) packetOfStep = workflowPlaylist.packetOf();
        List<ReplayStep> steps = workflowLoaded ? workflowPlaylist.steps()
            : listedSteps(QTraceReplayEngine.extractSteps(loadedQtraceJson, scope));
        List<ReplayStep> mmSteps = workflowLoaded ? List.of()
            : QTraceReplayEngine.extractMeasurementMapSteps(loadedQtraceJson, scope);
        preambleRunPreambleText = QTraceReplayEngine.buildPreambleScript(loadedQtraceJson, classifierTempDir, scope);
        populateRows(steps, mmSteps, currentCompat);
        if (currentPreflightInfo != null)
            infoLabel.setText(infoText(currentPreflightInfo, steps.size(), currentCompat, scope));
        setTransportEnabled(!steps.isEmpty());
        cursor = 0;
        refreshPacketStrip();
        preambleRun = false;
    }

    /**
     * The session the preamble is built for: a workflow's packet or a record's session when one
     * only is chosen, -1 for several packets or the record's whole history.
     */
    private int currentScope() {
        return selectedPackets.size() == 1 ? selectedPackets.first() : -1;
    }

    /** A workflow's playlist for the packets chosen, and which packet each instruction belongs to. */
    private record WorkflowPlaylist(List<ReplayStep> steps, java.util.IdentityHashMap<ReplayStep, Integer> packetOf) {}

    private static WorkflowPlaylist workflowPlaylist(String json, List<Integer> packets) {
        List<ReplayStep> steps = new ArrayList<>();
        java.util.IdentityHashMap<ReplayStep, Integer> packetOf = new java.util.IdentityHashMap<>();
        for (WorkflowSource.PacketStep ps : WorkflowSource.playlist(json, packets)) {
            steps.add(ps.step());
            packetOf.put(ps.step(), ps.packet());
        }
        return new WorkflowPlaylist(steps, packetOf);
    }

    // ── A workflow's packets ─────────────────────────────────────────────────

    /** Click: this packet only. Ctrl+click: adds it or takes it out. Shift+click: every packet up to it. */
    private void clickPacket(int packet, boolean toggle, boolean range) {
        if (runningProperty.get()) return;   // what plays is not changed in the middle of a run
        // A record's sessions are merged when several play: one session, or all of them.
        if (!workflowLoaded) toggle = range = false;
        if (range && lastClickedPacket >= 0) {
            for (int i = Math.min(lastClickedPacket, packet); i <= Math.max(lastClickedPacket, packet); i++) selectedPackets.add(i);
        } else if (toggle) {
            if (!selectedPackets.remove(packet)) selectedPackets.add(packet);
        } else {
            selectedPackets.clear();
            selectedPackets.add(packet);
        }
        lastClickedPacket = packet;
        applySessionScope();
    }

    private void selectAllPackets() {
        if (runningProperty.get()) return;
        selectedPackets.clear();
        for (int i = 0; i < packetTitles.size(); i++) selectedPackets.add(i);
        lastClickedPacket = -1;
        applySessionScope();
    }

    /** How a packet stands in the run: nothing played yet, being played, all played, or one instruction failed. */
    private enum PacketState { PENDING, CURRENT, DONE, FAILED }

    private PacketState packetState(int packet) {
        // What was played in this pass is what lies before the cursor: a row keeps the result of
        // an earlier pass (another target image) until it runs again.
        boolean any = false, left = false, failed = false;
        for (int i = 0; i < rows.size(); i++) {
            PlaylistRow row = rows.get(i);
            if (workflowLoaded && row.packet != packet) continue;   // a record's rows belong to no session
            any = true;
            if (i >= cursor) left = true;
            else if (row.result != null && row.result.status() == ReplayExecutor.Status.FAILED) failed = true;
        }
        if (!any || cursor == 0 && runningRow == null) return PacketState.PENDING;
        if (failed) return PacketState.FAILED;
        if (!left) return PacketState.DONE;
        PlaylistRow at = runningRow != null ? runningRow : cursor < rows.size() ? rows.get(cursor) : null;
        return at != null && (!workflowLoaded || at.packet == packet) ? PacketState.CURRENT : PacketState.PENDING;
    }

    /** Draws the strip again: which packets (or sessions of a record) are chosen, and where the run is. */
    private void refreshPacketStrip() {
        if (packetStrip == null) return;
        packetStrip.getChildren().clear();
        if (!workflowLoaded && !sessionStrip) return;
        Button all = new Button("All");
        all.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT_SUB + "; -fx-border-color: " + BORDER + ";"
            + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 3 8 3 8; -fx-font-size: 11;");
        all.setTooltip(new Tooltip(workflowLoaded ? "Play every packet of the workflow"
            : "Replay the whole history of this image: every session, each instruction once"));
        all.setOnAction(e -> selectAllPackets());
        all.disableProperty().bind(runningProperty);
        packetStrip.getChildren().add(all);

        for (int i = 0; i < packetTitles.size(); i++) {
            int packet = i;
            boolean chosen = selectedPackets.contains(packet);
            PacketState state = chosen ? packetState(packet) : PacketState.PENDING;
            String fill = !chosen ? "transparent" : switch (state) {
                case DONE -> GREEN;
                case FAILED -> RED;
                default -> PACKET;
            };
            Label number = new Label(state == PacketState.DONE ? "✓" : "#" + (packet + 1));
            number.setStyle("-fx-text-fill: " + (chosen ? BG : MUTED) + "; -fx-font-weight: bold; -fx-font-size: 12;");
            javafx.scene.layout.StackPane circle = new javafx.scene.layout.StackPane(number);
            circle.setMinSize(34, 34);
            circle.setMaxSize(34, 34);
            circle.setStyle("-fx-background-color: " + fill + "; -fx-background-radius: 17; -fx-border-radius: 17;"
                + "-fx-border-width: " + (state == PacketState.CURRENT ? 3 : 2) + ";"
                + "-fx-border-color: " + (state == PacketState.CURRENT ? BLUE : chosen ? fill : MUTED) + ";"
                + (chosen ? "" : "-fx-border-style: dashed;"));
            String title = packetTitles.get(packet).isBlank() ? (workflowLoaded ? "Packet " : "Session ") + (packet + 1) : packetTitles.get(packet);
            Label name = new Label(title.length() > 22 ? title.substring(0, 21) + "…" : title);
            name.setStyle("-fx-text-fill: " + (chosen ? TEXT : MUTED) + "; -fx-font-size: 10; -fx-font-weight: bold;");
            long count = workflowLoaded ? rows.stream().filter(r -> r.packet == packet).count()
                : packet < sessionInstructionCounts.size() ? sessionInstructionCounts.get(packet) : 0;
            Label sub = new Label(!chosen ? "not played" : state == PacketState.CURRENT ? "playing…" : count + (count == 1 ? " instruction" : " instructions"));
            sub.setStyle("-fx-text-fill: " + (state == PacketState.CURRENT ? BLUE : MUTED) + "; -fx-font-size: 9;");
            VBox node = new VBox(2, circle, name, sub);
            node.setAlignment(Pos.CENTER);
            node.setMinWidth(120);
            node.setCursor(javafx.scene.Cursor.HAND);
            Tooltip.install(node, new Tooltip(workflowLoaded
                ? title + "\nClick: this packet only · Ctrl+click: add or take out · Shift+click: up to here"
                : title + (packet == certifiedSession ? "\nThe session this certificate covers" : "")
                    + "\nClick: replay this session only · All: the whole history of the image"));
            node.setOnMouseClicked(e -> clickPacket(packet, e.isShortcutDown(), e.isShiftDown()));
            if (packet > 0) {
                Region line = new Region();
                line.setMinSize(24, 2);
                line.setMaxSize(24, 2);
                line.setStyle("-fx-background-color: " + BORDER + ";");
                HBox.setMargin(line, new Insets(16, 0, 0, 0));
                packetStrip.getChildren().add(line);
            }
            packetStrip.getChildren().add(node);
        }
    }

    /** "image — N step(s) [— certified session i/n] — compat X" line under the source field. */
    private String infoText(QTraceReplayEngine.PreflightInfo info, int stepCount,
                            QTracePreflightDialog.CompatResult compat, int scope) {
        if (workflowLoaded) {
            String title = WorkflowSource.title(loadedQtraceJson);
            String packet = selectedPackets.size() == sessionCount ? ""
                : selectedPackets.size() == 1 ? String.format(" — packet %d/%d", selectedPackets.first() + 1, sessionCount)
                : String.format(" — %d of %d packets", selectedPackets.size(), sessionCount);
            return String.format("Workflow (composed, not recorded) — %s — %d step(s)%s — compat %s",
                title.isBlank() ? "(untitled)" : title, stepCount, packet, compat.overall().label);
        }
        String image = info.imageName().isBlank() ? "(unknown image)" : info.imageName();
        String session = scope >= 0 && sessionCount > 1
            ? String.format(" — %ssession %d/%d", scope == certifiedSession ? "certified " : "", scope + 1, sessionCount) : "";
        return String.format("%s — %d step(s)%s — compat %s", image, stepCount, session, compat.overall().label);
    }

    private static int countSessions(String qtraceJson) {
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(qtraceJson).getAsJsonObject();
        return root.has("sessions") && root.get("sessions").isJsonArray() ? root.getAsJsonArray("sessions").size() : 0;
    }

    /** "Validated steps only": unchecks — or, when turned off, re-checks — the unstamped instructions. */
    private void applyValidatedOnly() {
        boolean only = validatedOnlyCheckBox.isSelected();
        for (PlaylistRow row : rows) {
            if (row.step.validated() || !row.step.isExecutable() || row.flaggedIncompatible) continue;
            row.included.set(!only);
        }
    }

    // ── Target image(s) ─────────────────────────────────────────────────────
    // Lists every image in the current project, checkbox per row — Play will open each checked
    // target in turn and replay the checked Instructions against it (see runBatch()). Starts
    // collapsed (unlike Pre-check, there's no "reserve" here to justify auto-opening).

    private record TargetRow(String displayName, ProjectImageEntry<BufferedImage> entry,
                              SimpleBooleanProperty selected) {}

    private VBox buildTargetImagesPanel() {
        targetChevron = new Label("▾");
        targetChevron.setTextFill(Color.web(MUTED));
        targetChevron.setFont(Font.font("System", 10));

        targetHeaderText = new Label();
        targetHeaderText.setTextFill(Color.web(MUTED));
        targetHeaderText.setFont(Font.font("System", FontWeight.BOLD, 11));

        HBox tLeft = new HBox(6, targetChevron, targetHeaderText);
        tLeft.setId("target-panel-header"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        tLeft.setAlignment(Pos.CENTER_LEFT);
        tLeft.setCursor(javafx.scene.Cursor.HAND);
        tLeft.setOnMouseClicked(e -> {
            targetExpanded = !targetExpanded;
            applyTargetExpanded();
        });

        Button refreshBtn = new Button("🔄 Rescan");
        refreshBtn.setFont(Font.font("System", 10));
        refreshBtn.setTextFill(Color.web(MUTED));
        refreshBtn.setStyle(
            "-fx-background-color: " + BG + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
        Tooltip.install(refreshBtn, new Tooltip("Rescan project images"));
        refreshBtn.setOnAction(e -> refreshTargetImages());

        Region tSpacer = new Region();
        HBox.setHgrow(tSpacer, Priority.ALWAYS);
        HBox targetHeaderRow = new HBox(8, tLeft, tSpacer, refreshBtn);
        targetHeaderRow.setAlignment(Pos.CENTER_LEFT);
        targetHeaderRow.setPadding(new Insets(7, 10, 7, 10));

        targetFilterField = new TextField();
        targetFilterField.setPromptText("Filter by name…");
        targetFilterField.setStyle(fieldStyle());
        HBox.setHgrow(targetFilterField, Priority.ALWAYS);
        targetFilterField.textProperty().addListener((obs, old, now) -> rebuildTargetNodes());

        Button selectAllBtn = new Button("Select all");
        selectAllBtn.setFont(Font.font("System", 10));
        selectAllBtn.setTextFill(Color.web(MUTED));
        selectAllBtn.setStyle(
            "-fx-background-color: " + BG + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
        selectAllBtn.disableProperty().bind(runningProperty);
        Tooltip.install(selectAllBtn, new Tooltip("Select every image as a Target"));
        selectAllBtn.setOnAction(e -> {
            for (TargetRow row : targetRows) row.selected().set(true);
        });

        HBox filterRow = new HBox(8, targetFilterField, selectAllBtn);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.setPadding(new Insets(8, 10, 6, 10));

        targetRowsBox = new VBox(0);
        targetScrollPane = new ScrollPane(targetRowsBox);
        targetScrollPane.setFitToWidth(true);
        targetScrollPane.setPrefHeight(220); // initial size only — the SplitPane divider governs it once inserted
        targetScrollPane.setStyle(
            "-fx-background: " + BG + "; -fx-background-color: " + BG + ";"
          + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4;");
        VBox.setVgrow(targetScrollPane, Priority.ALWAYS);

        targetDetailBox = new VBox(0, filterRow, targetScrollPane);
        targetDetailBox.setStyle(
            "-fx-background-color: " + SURFACE + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4;");
        VBox.setVgrow(targetDetailBox, Priority.ALWAYS); // once added below, absorbs whatever height the divider gives this item beyond the header

        // Header + detail are ONE splitPane item (not two separate items) — detail is added to
        // / removed from THIS VBox's own children (applyTargetExpanded), never touching
        // splitPane.getItems() itself. Two neighboring splitPane items each individually capped
        // to their own content (one for the header, one for the detail) turned out to fight each
        // other over the divider between them — with one side rigid, JavaFX sometimes refused to
        // let that divider move at all instead of just resizing the flexible side. A single item
        // whose OWN maxHeight toggles between "locked to header" (collapsed) and "unrestricted"
        // (expanded) sidesteps that entirely: there's only ever one divider involved, and its
        // constraint always comes from exactly one, unambiguous source.
        VBox panel = new VBox(targetHeaderRow);
        panel.setStyle(
            "-fx-background-color: " + SURFACE + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4;");
        panel.setMaxHeight(Region.USE_PREF_SIZE); // starts collapsed — locked to header height
        return panel;
    }

    /** Inserts {@code node} into splitPane's items right after {@code anchor} (or at the front if {@code anchor} is null/absent). */
    private void insertIntoSplitAfter(Node anchor, Node node) {
        if (splitPane.getItems().contains(node)) return;
        int idx = (anchor != null) ? splitPane.getItems().indexOf(anchor) + 1 : 0;
        splitPane.getItems().add(idx, node); // the new divider this creates picks up its dark style from the stylesheet installed in show() — no manual restyle needed
    }

    // Filter row (~40px) + 4 image rows (~33px each) — the ScrollPane's own computed min height
    // is tiny (just its scrollbar chrome), so without an explicit floor the SplitPane divider
    // was free to leave the freshly-expanded list at whatever sliver of space it already had
    // (as little as 1-2 rows) instead of opening up to a usable size.
    private static final double TARGET_EXPANDED_MIN_HEIGHT = 172;

    private void applyTargetExpanded() {
        targetChevron.setText(targetExpanded ? "▴" : "▾");
        if (targetExpanded) {
            if (!targetPanel.getChildren().contains(targetDetailBox)) targetPanel.getChildren().add(targetDetailBox);
            targetPanel.setMaxHeight(Double.MAX_VALUE); // now something to actually resize — let the divider move freely
            targetPanel.setMinHeight(TARGET_EXPANDED_MIN_HEIGHT); // ensures ~4 rows are visible right away
        } else {
            targetPanel.getChildren().remove(targetDetailBox);
            targetPanel.setMaxHeight(Region.USE_PREF_SIZE); // nothing left but the header — lock back down, no dead space
            targetPanel.setMinHeight(Region.USE_PREF_SIZE);
        }
    }

    /**
     * Shows/hides Pre-check's header block — a splitPane item, always inserted at the very
     * front so Pre-check reliably lands above the whole Target block.
     */
    private void setPrecheckVisible(boolean visible) {
        if (visible) {
            insertIntoSplitAfter(null, precheckPanel);
        } else {
            precheckPanel.getChildren().remove(precheckDetailBox);
            splitPane.getItems().remove(precheckPanel);
        }
    }

    /**
     * A real stylesheet rule for {@code .split-pane-divider}, rather than restyling individual
     * divider nodes after the fact — applies automatically to every divider, including ones
     * created later when a detail pane gets inserted into splitPane.
     */
    private void installDarkSplitDividerCss(Scene scene) {
        // Vertical orientation → dividers are horizontal bars; top/bottom padding is what
        // gives the mouse a grabbable strip. 0 there (as briefly shipped) made the divider
        // nearly un-draggable — dragging is the whole point of a SplitPane divider.
        String css = ".split-pane-divider { -fx-background-color: " + BORDER + "; -fx-padding: 2px; }";
        try {
            String encoded = java.net.URLEncoder.encode(css, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            scene.getStylesheets().add("data:text/css," + encoded);
        } catch (Exception ignored) {}
    }

    /**
     * Re-scans the current project (or falls back to the single open image outside a project)
     * for the Target image(s) list. Only the currently active image starts checked — everything
     * else is an explicit opt-in.
     */
    private void refreshTargetImages() {
        targetRows.clear();
        Project<BufferedImage> project = qupath.getProject();
        if (project != null) {
            ImageData<BufferedImage> current = qupath.getImageData();
            ProjectImageEntry<BufferedImage> active = current != null ? project.getEntry(current) : null;
            for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
                targetRows.add(new TargetRow(entry.getImageName(), entry,
                    new SimpleBooleanProperty(entry.equals(active))));
            }
        } else if (qupath.getImageData() != null) {
            String name = qupath.getImageData().getServer().getMetadata().getName();
            targetRows.add(new TargetRow("Target : " + name, null, new SimpleBooleanProperty(true)));
        }
        // Registered once per row here, not in rebuildTargetNodes() — that method reruns on
        // every filter keystroke, which would otherwise pile up a duplicate listener per keystroke.
        for (TargetRow row : targetRows) row.selected().addListener((obs, was, now) -> updateTargetHeaderSummary());
        rebuildTargetNodes();
        updateTargetHeaderSummary();
    }

    /**
     * Rebuilds the visible rows from {@link #targetRows}, filtered by
     * {@link #targetFilterField}'s free-text (case-insensitive substring match against the
     * image name). Filtering only hides rows — a checked-but-filtered-out image stays checked
     * (the {@link SimpleBooleanProperty} lives on the row, not on the node), so narrowing the
     * list to pick one more target never silently unchecks earlier picks.
     */
    private void rebuildTargetNodes() {
        targetRowsBox.getChildren().clear();
        String filter = targetFilterField.getText() == null ? "" : targetFilterField.getText().strip().toLowerCase();

        for (TargetRow row : targetRows) {
            if (!filter.isEmpty() && !row.displayName().toLowerCase().contains(filter)) continue;
            // While a batch is running, only the images actually in play stay visible — no point
            // scrolling past 30 unchecked images to see which of the 3 checked ones is running.
            if (targetFilterToChecked && !row.selected().get()) continue;

            CheckBox cb = new CheckBox();
            cb.selectedProperty().bindBidirectional(row.selected());
            cb.setStyle("-fx-text-fill: " + TEXT + ";");
            cb.disableProperty().bind(runningProperty);

            Label nameLbl = new Label(row.displayName());
            boolean active = row == activeTargetRow;
            nameLbl.setTextFill(Color.web(active ? GREEN : TEXT));
            nameLbl.setFont(Font.font("System", active ? FontWeight.BOLD : FontWeight.NORMAL, 11));
            nameLbl.setWrapText(true);
            HBox.setHgrow(nameLbl, Priority.ALWAYS);

            HBox rowBox = new HBox(8, cb, nameLbl);
            rowBox.setAlignment(Pos.CENTER_LEFT);
            rowBox.setPadding(new Insets(5, 10, 5, 10));
            rowBox.setStyle(
                (active ? "-fx-background-color: " + GREEN_SOFT + ";" : "")
              + "-fx-border-color: " + BORDER + " transparent transparent transparent; -fx-border-width: 1 0 0 0;");
            targetRowsBox.getChildren().add(rowBox);
        }

        if (!filter.isEmpty() && targetRowsBox.getChildren().isEmpty()) {
            Label empty = new Label("No image matches \"" + targetFilterField.getText().strip() + "\"");
            empty.setTextFill(Color.web(MUTED));
            empty.setFont(Font.font("System", 11));
            empty.setPadding(new Insets(8, 10, 8, 10));
            targetRowsBox.getChildren().add(empty);
        }
    }

    private void updateTargetHeaderSummary() {
        if (targetRows.isEmpty()) {
            targetHeaderText.setText("Target image(s) — Select at least one image");
        } else if (targetRows.size() == 1 && targetRows.get(0).entry() == null) {
            targetHeaderText.setText("Target image(s) — " + targetRows.get(0).displayName());
        } else {
            long checked = targetRows.stream().filter(r -> r.selected().get()).count();
            targetHeaderText.setText("Target image(s) — " + checked + "/" + targetRows.size() + " selected");
        }
    }

    // ── Pre-check ────────────────────────────────────────────────────────────
    // Runs right after the file opens, before Instructions is touched. Always expandable —
    // the header (chevron + verdict) toggles the detail rows on click, whether the check is
    // green or orange. It only *opens itself* automatically when there's a reserve, so the
    // user sees what needs attention without an extra click; a clean check stays collapsed
    // but is never hidden away, so "what was checked" is always a click away.

    private VBox buildPrecheckPanel() {
        precheckChevron = new Label("▾");
        precheckChevron.setTextFill(Color.web(MUTED));
        precheckChevron.setFont(Font.font("System", 10));

        precheckHeaderText = new Label();
        precheckHeaderText.setFont(Font.font("System", FontWeight.BOLD, 11));

        HBox pcLeft = new HBox(6, precheckChevron, precheckHeaderText);
        pcLeft.setAlignment(Pos.CENTER_LEFT);
        pcLeft.setCursor(javafx.scene.Cursor.HAND);
        pcLeft.setOnMouseClicked(e -> {
            precheckExpanded = !precheckExpanded;
            applyPrecheckExpanded();
        });

        Button recheckBtn = new Button("🔄 Check my config");
        recheckBtn.setFont(Font.font("System", 10));
        recheckBtn.setTextFill(Color.web(MUTED));
        recheckBtn.setStyle(
            "-fx-background-color: " + BG + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
        Tooltip.install(recheckBtn, new Tooltip(
            "Verifies your local configuration before running: file integrity, required "
          + "extensions, referenced ML models, and QuPath version."));
        recheckBtn.setOnAction(e -> rerunPrecheck());

        Region pcSpacer = new Region();
        HBox.setHgrow(pcSpacer, Priority.ALWAYS);
        HBox precheckHeaderRow = new HBox(8, pcLeft, pcSpacer, recheckBtn);
        precheckHeaderRow.setAlignment(Pos.CENTER_LEFT);
        precheckHeaderRow.setPadding(new Insets(7, 10, 7, 10));

        // Added to / removed from panel's own children (applyPrecheckExpanded) — not a
        // splitPane item itself, same header+detail-as-one-item approach as Target.
        precheckDetailBox = new VBox(0);
        precheckDetailBox.setStyle(
            "-fx-background-color: " + SURFACE + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4;");

        VBox panel = new VBox(precheckHeaderRow);
        panel.setId("precheck-panel"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        panel.setStyle(
            "-fx-background-color: " + SURFACE + "; -fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4;");
        // Always capped to content (header alone, or header+4 check rows) — Pre-check never
        // needs real resize, so unlike Target this stays USE_PREF_SIZE in both expand states.
        // Not visible/managed-toggled either — it's a splitPane item, added/removed wholesale
        // by setPrecheckVisible() instead (starts absent: no .qtrace loaded yet).
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        return panel;
    }

    private void applyPrecheckExpanded() {
        precheckChevron.setText(precheckExpanded ? "▴" : "▾");
        if (precheckExpanded) {
            if (!precheckPanel.getChildren().contains(precheckDetailBox)) precheckPanel.getChildren().add(precheckDetailBox);
        } else {
            precheckPanel.getChildren().remove(precheckDetailBox);
        }
        // precheckPanel's own maxHeight stays USE_PREF_SIZE in both states — unlike Target,
        // Pre-check never needs real resize (a handful of fixed check rows), it should just
        // always hug whatever its content currently is.
    }

    private void rerunPrecheck() {
        if (currentPreflightInfo == null) return;
        new Thread(() -> {
            QTracePreflightDialog.CompatResult compat = QTracePreflightDialog.check(qupath, currentPreflightInfo);
            Platform.runLater(() -> renderPrecheck(currentPreflightInfo, compat));
        }, "qtrace-precheck-rerun").start();
    }

    private void renderPrecheck(QTraceReplayEngine.PreflightInfo info, QTracePreflightDialog.CompatResult compat) {
        precheckDetailBox.getChildren().clear();

        // Fichier — the same hash used for sourceQtraceHash / the ReplayStamp; there's no
        // embedded reference hash to compare against yet, so this row confirms the file
        // parsed and a hash was computed, not that it matches a prior signature.
        precheckDetailBox.getChildren().add(precheckRow("🟢", "File",
            "Intact — hash computed (" + sourceQtraceHash.substring(0, 12) + "…)", GREEN));

        boolean extensionsOk;
        String extensionsText;
        if (info.requiredExtensions().isEmpty()) {
            extensionsOk = true;
            extensionsText = "No extension required";
        } else {
            List<String> missing = compat.extensions().stream()
                .filter(c -> !c.present()).map(QTracePreflightDialog.ExtCheck::name).collect(Collectors.toList());
            extensionsOk = missing.isEmpty();
            extensionsText = extensionsOk
                ? compat.extensions().size() + "/" + compat.extensions().size() + " present"
                : "Missing: " + String.join(", ", missing);
        }
        precheckDetailBox.getChildren().add(precheckRow(extensionsOk ? "🟢" : "🟡", "Extensions",
            extensionsText, extensionsOk ? GREEN : PEACH));

        boolean modelsOk;
        String modelsText;
        if (info.modelPaths().isEmpty()) {
            modelsOk = true;
            modelsText = "No model referenced";
        } else {
            List<String> missing = compat.modelStatuses().entrySet().stream()
                .filter(en -> en.getValue() == QTraceExtensionHandler.ModelStatus.MISSING)
                .map(Map.Entry::getKey).collect(Collectors.toList());
            List<String> mismatchedNames = compat.modelStatuses().entrySet().stream()
                .filter(en -> en.getValue() == QTraceExtensionHandler.ModelStatus.VERSION_MISMATCH)
                .map(Map.Entry::getKey).collect(Collectors.toList());
            modelsOk = missing.isEmpty() && mismatchedNames.isEmpty();
            if (modelsOk) {
                modelsText = "All present locally (" + compat.modelStatuses().size() + ")";
            } else {
                List<String> parts = new ArrayList<>();
                if (!missing.isEmpty()) parts.add("To download: " + String.join(", ", missing));
                if (!mismatchedNames.isEmpty()) {
                    var instanSeg = io.qtrace.player.ext.ExtensionHandlerRegistry.get().findByName("instanseg");
                    List<String> mismatchDetails = mismatchedNames.stream().map(name -> {
                        String originalPath = info.modelPaths().get(name);
                        String sourceVersion = io.qtrace.player.ext.InstanSegHandler.lastPathSegment(originalPath);
                        String localVersion = instanSeg.map(h -> h.findLocalModelVersion(name, originalPath)).orElse(null);
                        return name + " (source: " + (sourceVersion != null ? sourceVersion : name)
                            + " → local: " + (localVersion != null ? localVersion : "unknown") + ")";
                    }).collect(Collectors.toList());
                    parts.add("Different version installed, results may differ: " + String.join(", ", mismatchDetails));
                }
                modelsText = String.join("; ", parts);
            }
        }
        precheckDetailBox.getChildren().add(precheckRow(modelsOk ? "🟢" : "🟡", "ML Models",
            modelsText, modelsOk ? GREEN : PEACH));

        String localVersion = qupath.getVersion() != null ? qupath.getVersion().toString() : "unknown";
        String qtraceVersion = info.qupathVersion().isBlank() ? "unknown" : info.qupathVersion();
        boolean versionOk = qtraceVersion.equals(localVersion) || qtraceVersion.equals("unknown");
        precheckDetailBox.getChildren().add(precheckRow(versionOk ? "🟢" : "🟡", "QuPath Version",
            versionOk ? qtraceVersion + " — matches this machine"
                      : qtraceVersion + " → this machine is on " + localVersion, versionOk ? GREEN : PEACH));

        // Pixel classifiers — downloaded (or attempted) in loadQtrace(), before this render call;
        // currentClassifierResult stays null only when there was nothing to download.
        boolean classifiersOk;
        String classifiersText;
        String classifiersDot;
        String classifiersColor;
        if (currentClassifierNames.isEmpty()) {
            classifiersOk = true;
            classifiersDot = "🟢";
            classifiersColor = GREEN;
            classifiersText = "No classifier required";
        } else if (currentClassifierResult == null) {
            classifiersOk = false;
            classifiersDot = "🟡";
            classifiersColor = PEACH;
            classifiersText = "Not checked yet";
        } else if (currentClassifierResult.failed().isEmpty()) {
            classifiersOk = true;
            classifiersDot = "🟢";
            classifiersColor = GREEN;
            classifiersText = "All present locally (" + currentClassifierResult.succeeded().size() + ")";
        } else {
            classifiersOk = false;
            classifiersDot = "🔴";
            classifiersColor = RED;
            classifiersText = currentClassifierResult.failed().size() + " missing — "
                + String.join(", ", currentClassifierResult.failed().keySet());
        }
        precheckDetailBox.getChildren().add(precheckRow(classifiersDot, "Pixel Classifiers",
            classifiersText, classifiersColor));

        boolean allOk = extensionsOk && modelsOk && versionOk && classifiersOk;
        precheckHeaderText.setText(allOk ? "✓ Check OK" : "⚠ Check your configuration");
        precheckHeaderText.setTextFill(Color.web(allOk ? GREEN : PEACH));

        if (!allOk) precheckExpanded = true; // reserve found → open itself, no extra click needed
        applyPrecheckExpanded();
    }

    private HBox precheckRow(String dot, String label, String detail, String color) {
        Label dotLbl = new Label(dot);
        dotLbl.setFont(Font.font("System", 10));
        dotLbl.setMinWidth(18);

        Label labelLbl = new Label(label);
        labelLbl.setTextFill(Color.web(TEXT));
        labelLbl.setFont(Font.font("System", FontWeight.BOLD, 10));
        labelLbl.setMinWidth(90);

        Label detailLbl = new Label(detail);
        detailLbl.setTextFill(Color.web(color));
        detailLbl.setFont(Font.font("System", 10));
        detailLbl.setWrapText(true);
        HBox.setHgrow(detailLbl, Priority.ALWAYS);

        HBox row = new HBox(8, dotLbl, labelLbl, detailLbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5, 10, 5, 10));
        row.setStyle("-fx-border-color: " + BORDER + " transparent transparent transparent; -fx-border-width: 1 0 0 0;");
        return row;
    }

    // ── Playlist (dashboard-style row list — no TableView, no trailing blank filler rows) ──

    private StackPane buildPlaylistSection() {
        // Select all / unselect all — a single toggle above the row checkboxes, not a
        // tri-state indicator: it always sets every row to the same state it now shows.
        selectAllCheckBox = new CheckBox();
        selectAllCheckBox.setSelected(true);
        selectAllCheckBox.setStyle("-fx-text-fill: " + TEXT + ";");
        selectAllCheckBox.setOnAction(e -> {
            boolean state = selectAllCheckBox.isSelected();
            for (PlaylistRow row : rows)
                if (!row.step.skipped()) row.included.set(state);
        });
        selectAllCheckBox.disableProperty().bind(runningProperty.or(javafx.beans.binding.Bindings.isEmpty(rows)));

        Label sectionTitle = new Label("Instructions");
        sectionTitle.setId("instructions-header"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        sectionTitle.setTextFill(Color.web(TEXT_SUB));
        sectionTitle.setFont(Font.font("System", FontWeight.BOLD, 11));
        sectionTitle.setPadding(new Insets(0, 0, 4, 2));

        validatedOnlyCheckBox = new CheckBox("Validated steps only");
        validatedOnlyCheckBox.setTextFill(Color.web(TEXT_SUB));
        validatedOnlyCheckBox.setFont(Font.font("System", 11));
        Tooltip.install(validatedOnlyCheckBox, new Tooltip(
            "Leave out the instructions marked ◌ — recorded without a stamp, never validated"));
        validatedOnlyCheckBox.setOnAction(e -> applyValidatedOnly());
        validatedOnlyCheckBox.disableProperty().bind(runningProperty.or(javafx.beans.binding.Bindings.isEmpty(rows)));
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        packetStrip = new HBox(6);
        packetStrip.setAlignment(Pos.TOP_LEFT);
        packetStrip.setPadding(new Insets(8, 10, 8, 10));
        packetScroll = new ScrollPane(packetStrip);
        packetScroll.setFitToHeight(true);
        packetScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        packetScroll.setMinHeight(92);
        packetScroll.setStyle("-fx-background: " + BG + "; -fx-background-color: " + BG + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4;");
        packetScroll.setVisible(false);
        packetScroll.setManaged(false);
        HBox titleRow = new HBox(8, sectionTitle, titleSpacer, validatedOnlyCheckBox);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(10,
            selectAllCheckBox,
            resizableHeaderCell("TYPE", typeColWidth, 50),
            resizableHeaderCell("TITLE", titleColWidth, 80),
            resizableHeaderCell("STEP", stepColWidth, 40),
            resizableHeaderCell("STATUS", statusColWidth, 60),
            resizableHeaderCell("TIMER", timerColWidth, 40));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 10, 6, 10));
        header.setStyle("-fx-border-color: transparent transparent " + BORDER + " transparent; -fx-border-width: 0 0 1 0;");

        playlistBox = new VBox();
        playlistScroll = new ScrollPane(playlistBox);
        playlistScroll.setId("playlist-body"); // looked up by the screenshot harness — see PlayerScreenshotHarness
        playlistScroll.setFitToWidth(true);
        playlistScroll.setStyle(
            "-fx-background: " + BG + "; -fx-background-color: " + BG + ";"
          + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4;");
        VBox.setVgrow(playlistScroll, Priority.ALWAYS);

        VBox.setMargin(packetScroll, new Insets(0, 0, 6, 0));
        VBox content = new VBox(0, titleRow, packetScroll, header, playlistScroll);

        // No overlay here anymore — Instructions stays fully visible and updates live, row by
        // row, while a run executes (see applyResult()). The "is it working" signal moved to a
        // small spinner in the Console footer instead (see show()); the only thing this section
        // still needs during a run is to stop the checkboxes from being toggled out from under
        // an in-progress run — handled per-row via runningProperty (see rebuildPlaylistNodes()).
        return new StackPane(content);
    }

    /**
     * Where this run's log file goes: {@code <project>/qTrace/logs} in Project Folder mode (when
     * a project is actually open), else the configured/default Logs directory from Settings.
     */
    private java.nio.file.Path resolveLogsDir() {
        Project<BufferedImage> project = qupath.getProject();
        java.nio.file.Path projectBaseDir = (project != null && project.getPath() != null)
            ? project.getPath().getParent() : null;
        return QTraceConfig.get().resolveLogsDir(projectBaseDir);
    }

    /**
     * Opens the current run's log file directly in the OS default text viewer/editor, or the
     * replay-logs folder itself when there's no run yet to point at. Never uses
     * java.awt.Desktop: initializing AWT inside this JavaFX/GTK process can crash the JVM on
     * Linux (same reason QTracePreflightDialog's "Install →" shells out to xdg-open instead).
     */
    private void openLogsFolder() {
        java.nio.file.Path target = logWriter != null
            ? logWriter.logPath()
            : resolveLogsDir();
        try {
            java.nio.file.Files.createDirectories(logWriter != null ? target.getParent() : target);
        } catch (Exception ignored) {}

        new Thread(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("linux"))
                    pb = new ProcessBuilder("xdg-open", target.toString());
                else if (os.contains("mac"))
                    pb = new ProcessBuilder("open", target.toString());
                else
                    pb = new ProcessBuilder("cmd", "/c", "start", target.toString());
                pb.start();
            } catch (Exception ignored) {}
        }, "qtrace-open-logs").start();
    }

    /**
     * Grey once (no log for this run yet) / green once {@link #startNewRun()} has actually
     * opened a log file on disk — a quick "is there something to open" signal without needing
     * to click through to the folder.
     */
    private void setLogsButtonState(boolean hasLog) {
        openLogsBtn.setGraphic(buildLogIcon(hasLog ? GREEN : MUTED));
    }

    /** Small vector "log file" icon — page with a folded corner, "</>" and "LOG" inside. */
    private Node buildLogIcon(String color) {
        Color c = Color.web(color);

        Path page = new Path(
            new MoveTo(3, 1.5), new LineTo(9.5, 1.5), new LineTo(13, 5),
            new LineTo(13, 14.5), new LineTo(3, 14.5), new ClosePath());
        page.setStroke(c);
        page.setStrokeWidth(1.3);
        page.setFill(Color.TRANSPARENT);
        page.setStrokeLineJoin(StrokeLineJoin.ROUND);

        Path fold = new Path(new MoveTo(9.5, 1.5), new LineTo(9.5, 5), new LineTo(13, 5));
        fold.setStroke(c);
        fold.setStrokeWidth(1.3);
        fold.setFill(Color.TRANSPARENT);
        fold.setStrokeLineJoin(StrokeLineJoin.ROUND);

        Text code = new Text("</>");
        code.setFill(c);
        code.setFont(Font.font("Monospaced", FontWeight.BOLD, 4.6));
        code.setX(4.4);
        code.setY(9.3);

        Text log = new Text("LOG");
        log.setFill(c);
        log.setFont(Font.font("System", FontWeight.BOLD, 3.4));
        log.setX(5);
        log.setY(13.2);

        Group icon = new Group(page, fold, code, log);
        icon.setScaleX(1.15);
        icon.setScaleY(1.15);
        return icon;
    }

    private void showErrorDialog(PlaylistRow row) {
        ReplayExecutor.StepResult result = row.result;

        TextArea detail = new TextArea();
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setStyle(
            "-fx-control-inner-background: " + SURFACE + ";"
          + "-fx-text-fill: " + TEXT + ";"
          + "-fx-font-family: monospace;"
          + "-fx-font-size: 11;");
        detail.setPrefSize(640, 380);
        detail.setText(
            "Step " + row.step.index() + " — " + row.step.type() + "\n"
          + row.step.title() + "\n\n"
          + "── Fragment ─────────────────────────────────────\n"
          + row.step.groovyFragment() + "\n\n"
          + "── Error ────────────────────────────────────────\n"
          + (result.errorMessage() != null ? result.errorMessage() : "(no message)") + "\n\n"
          + (result.stackTrace() != null ? result.stackTrace() : ""));

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("qTrace Player — Step " + row.step.index() + " failed");
        alert.setHeaderText(result.errorMessage() != null ? result.errorMessage() : "Step failed");
        alert.getDialogPane().setContent(detail);
        alert.getDialogPane().setStyle("-fx-background-color: " + BG + ";");
        alert.initOwner(stage);
        alert.setResizable(true);
        alert.showAndWait();
    }

    private void rebuildPlaylistNodes() {
        rowViews.clear();
        playlistBox.getChildren().clear();
        int total = rows.size();
        int packetShown = -1;
        for (int i = 0; i < total; i++) {
            PlaylistRow row = rows.get(i);
            String baseStyle = "-fx-background-color: " + (i % 2 == 0 ? SURFACE : ROW_ALT) + ";";

            // A workflow's instructions come packet after packet: each packet opens on its title.
            if (row.packet >= 0 && row.packet != packetShown) {
                packetShown = row.packet;
                String title = row.packet < packetTitles.size() ? packetTitles.get(row.packet) : "";
                Label head = new Label("#" + (row.packet + 1) + (title.isBlank() ? "" : " — " + title));
                head.setStyle("-fx-text-fill: " + PACKET + "; -fx-font-weight: bold; -fx-font-size: 11;");
                HBox separator = new HBox(head);
                separator.setPadding(new Insets(8, 10, 4, 10));
                separator.setStyle("-fx-background-color: " + BG + "; -fx-border-color: " + BORDER + "; -fx-border-width: 0 0 1 0;");
                playlistBox.getChildren().add(separator);
            }

            boolean executable = row.step.isExecutable();

            CheckBox cb = new CheckBox();
            cb.selectedProperty().bindBidirectional(row.included);
            if (executable) {
                cb.disableProperty().bind(runningProperty); // grey out the checkbox column instead of overlaying the whole list
            } else {
                // Non-executable row (e.g. a Measurement Map view) — no script_fragment for the
                // executor to run, so the checkbox stays permanently unchecked and disabled.
                cb.setDisable(true);
            }

            Label typeLbl = rowLabel(row.step.type(), typeColor(row.step.type()), typeColWidth);
            // ◌ = recorded only in unstamped sessions: documented, never validated by a stamp.
            boolean skipped = row.step.skipped();
            // A workflow's instructions carry no stamp by nature: they are not marked as unvalidated work.
            boolean plain = row.step.validated() || workflowLoaded;
            Label titleLbl = new Label((plain ? "" : "◌ ") + row.step.title());
            titleLbl.setTextFill(Color.web(skipped ? MUTED : plain ? TEXT : PEACH));
            titleLbl.setFont(Font.font("System", 11));
            titleLbl.setTextOverrun(OverrunStyle.ELLIPSIS);
            titleLbl.setMinWidth(0);
            HBox.setHgrow(titleLbl, Priority.ALWAYS);
            // The eye opens, under the row, the script exactly as it will be run. A row with
            // nothing to run keeps the room, so the titles stay in line.
            VBox scriptBox = executable ? scriptBox(row) : null;
            Button eye = new Button();
            eye.setMinSize(24, 24);
            eye.setMaxSize(24, 24);
            eye.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-cursor: hand;");
            eye.setVisible(executable);
            if (executable) {
                Tooltip eyeTip = new Tooltip();
                eye.setTooltip(eyeTip);
                Runnable show = () -> {
                    scriptBox.setVisible(row.expanded);
                    scriptBox.setManaged(row.expanded);
                    eye.setGraphic(eyeIcon(Color.web(row.expanded ? BLUE : MUTED)));
                    eyeTip.setText(row.expanded ? "Hide the script" : "Show the script exactly as it will be run");
                };
                eye.setOnAction(e -> { row.expanded = !row.expanded; show.run(); });
                show.run();
            }
            HBox titleCell = new HBox(4, eye, titleLbl);
            titleCell.setAlignment(Pos.CENTER_LEFT);
            titleCell.prefWidthProperty().bind(titleColWidth);
            titleCell.minWidthProperty().bind(titleColWidth);
            titleCell.maxWidthProperty().bind(titleColWidth);
            if (skipped)
                Tooltip.install(titleLbl, new Tooltip(
                    "Taken out of the replay by its author — kept in the trace, never run"));
            else if (!plain)
                Tooltip.install(titleLbl, new Tooltip("Unstamped — recorded without a stamp, not validated"
                    + (row.step.sessionId() != null ? " (session " + row.step.sessionId().substring(0,
                        Math.min(8, row.step.sessionId().length())) + ")" : "")));
            Label stepLbl = rowLabel(row.step.index() + " / " + total, TEXT_SUB, stepColWidth);
            Label statusLbl = rowLabel(
                skipped ? "Excluded by the author"
                    : !executable ? "⚠ Not replayable — execute manually"
                    : row.flaggedIncompatible ? "Skipped (incompatible)" : "—",
                skipped ? MUTED : !executable || row.flaggedIncompatible ? PEACH : MUTED, statusColWidth);
            Label timerLbl = rowLabel("—", MUTED, timerColWidth);

            HBox node = new HBox(10, cb, typeLbl, titleCell, stepLbl, statusLbl, timerLbl);
            node.setAlignment(Pos.CENTER_LEFT);
            node.setPadding(new Insets(2, 10, 2, 10));
            node.setStyle(baseStyle);
            if (skipped) node.setOpacity(0.5);

            // Rows don't move the cursor: the replay runs strictly in playlist order (the
            // checkboxes choose what runs). Only a failed row reacts to a click, opening its
            // full error — the status column fits just a one-line summary (rowLabel width=150).
            node.setOnMouseClicked(e -> {
                if (row.result != null && row.result.status() == ReplayExecutor.Status.FAILED)
                    showErrorDialog(row);
            });

            rowViews.put(row, new RowView(node, statusLbl, timerLbl, baseStyle));
            playlistBox.getChildren().add(node);
            if (scriptBox != null) playlistBox.getChildren().add(scriptBox);
        }
        refreshPacketStrip();
    }

    /** The script of an instruction, as the executor will run it — read-only, shown under its row. */
    private VBox scriptBox(PlaylistRow row) {
        String script = row.step.groovyFragment().strip();
        TextArea code = new TextArea(script);
        code.setEditable(false);
        code.setWrapText(false);
        code.setStyle("-fx-control-inner-background: " + BG + "; -fx-text-fill: " + TEXT + ";"
            + "-fx-font-family: monospace; -fx-font-size: 11; -fx-background-color: " + BG + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4;");
        // As tall as the script up to 16 lines, then it scrolls.
        int lines = (int) Math.min(16, Math.max(1, script.lines().count()));
        double height = lines * 16 + 16;
        code.setMinHeight(height);
        code.setPrefHeight(height);
        code.setMaxHeight(height);
        VBox box = new VBox(code);
        box.setPadding(new Insets(2, 10, 8, 44));
        box.setStyle("-fx-background-color: " + BG + ";");
        return box;
    }

    /** An eye on the panel's 24-unit grid (outline, round joins), drawn at 16 px. */
    private static Group eyeIcon(Color c) {
        Path lids = new Path(new MoveTo(2.5, 12), new QuadCurveTo(12, 3.5, 21.5, 12),
            new QuadCurveTo(12, 20.5, 2.5, 12), new ClosePath());
        Circle pupil = new Circle(12, 12, 3);
        for (Shape sh : new Shape[] {lids, pupil}) {
            sh.setFill(Color.TRANSPARENT);
            sh.setStroke(c);
            sh.setStrokeWidth(1.7);
            sh.setStrokeLineCap(StrokeLineCap.ROUND);
            sh.setStrokeLineJoin(StrokeLineJoin.ROUND);
        }
        Group inner = new Group(lids, pupil);
        inner.setScaleX(16.0 / 24);
        inner.setScaleY(16.0 / 24);
        return new Group(inner);
    }

    private String typeColor(String type) {
        return switch (type) {
            case "Pixel Classifier" -> BLUE;
            case "Segmentation"     -> PEACH;
            case "Cell detection"   -> GREEN;
            case "Measurement Map"  -> PEACH; // display-only / not scriptable — same warning tone as Segmentation
            case "Display settings" -> BLUE;  // scriptable — same tone as Pixel Classifier, not a warning
            default                 -> MUTED; // "Script"
        };
    }

    private void highlightRow(PlaylistRow row) {
        if (highlighted != null) {
            RowView prev = rowViews.get(highlighted);
            if (prev != null) prev.node.setStyle(prev.baseStyle);
        }
        highlighted = row;
        RowView rv = rowViews.get(row);
        if (rv != null) {
            rv.node.setStyle(rv.baseStyle + "-fx-border-color: " + BLUE + "; -fx-border-width: 0 0 0 3;");
        }
        refreshPacketStrip();
    }

    private void scrollToRow(PlaylistRow row) {
        RowView rv = rowViews.get(row);
        if (rv == null) return;
        Platform.runLater(() -> {
            double contentHeight = playlistBox.getHeight();
            double viewportHeight = playlistScroll.getViewportBounds() != null
                ? playlistScroll.getViewportBounds().getHeight() : 0;
            double range = Math.max(1, contentHeight - viewportHeight);
            double y = rv.node.getBoundsInParent().getMinY();
            playlistScroll.setVvalue(Math.max(0, Math.min(1, y / range)));
        });
    }

    private static Label headerLabel(String text, double width) {
        Label l = new Label(text);
        l.setTextFill(Color.web(MUTED));
        l.setFont(Font.font("System", FontWeight.BOLD, 9));
        l.setStyle("-fx-letter-spacing: 0.5px;");
        if (width > 0) l.setPrefWidth(width);
        return l;
    }

    /**
     * Fixed-width row label bound to a shared column-width property — used together with
     * {@link #resizableHeaderCell} so a column's header and every row's cell always share the
     * exact same width (min = max = pref, so long content clips instead of bleeding into the
     * next column and silently misaligning everything below the header).
     */
    private static Label rowLabel(String text, String color, javafx.beans.property.DoubleProperty widthProperty) {
        Label l = new Label(text);
        l.setTextFill(Color.web(color));
        l.setFont(Font.font("System", 11));
        l.setTextOverrun(OverrunStyle.ELLIPSIS);
        l.prefWidthProperty().bind(widthProperty);
        l.minWidthProperty().bind(widthProperty);
        l.maxWidthProperty().bind(widthProperty);
        return l;
    }

    /**
     * Header cell for a fixed-width, user-resizable column: the label itself plus a thin
     * drag handle overlaid on its right edge (stacked, not appended — so it adds zero extra
     * width to the header HBox and stays pixel-aligned with the row cells below, which bind
     * to the same {@code widthProperty} via {@link #rowLabel(String, String, javafx.beans.property.DoubleProperty)}).
     */
    private Node resizableHeaderCell(String text, javafx.beans.property.DoubleProperty widthProperty, double minWidth) {
        Label label = headerLabel(text, -1);
        label.prefWidthProperty().bind(widthProperty);
        label.minWidthProperty().bind(widthProperty);
        label.maxWidthProperty().bind(widthProperty);

        Region handle = new Region();
        handle.setPrefWidth(6);
        handle.setMinWidth(6);
        handle.setMaxWidth(6);
        handle.setCursor(javafx.scene.Cursor.H_RESIZE);
        StackPane.setAlignment(handle, Pos.CENTER_RIGHT);

        StackPane cell = new StackPane(label, handle);
        cell.prefWidthProperty().bind(widthProperty);
        cell.minWidthProperty().bind(widthProperty);
        cell.maxWidthProperty().bind(widthProperty);

        double[] drag = new double[2]; // [startScreenX, startWidth]
        handle.setOnMousePressed(e -> {
            drag[0] = e.getScreenX();
            drag[1] = widthProperty.get();
            e.consume();
        });
        handle.setOnMouseDragged(e -> {
            double delta = e.getScreenX() - drag[0];
            widthProperty.set(Math.max(minWidth, drag[1] + delta));
            e.consume();
        });
        return cell;
    }

    // ── Transport ────────────────────────────────────────────────────────────

    // No ⏮ / ◀: a step already applied to the image can't be undone in QuPath, so the
    // transport only moves forward.

    /**
     * A single step must run against the right image too — not just Play/batch. If the
     * currently open image already matches one of the checked Target(s), runs immediately;
     * otherwise opens the first checked Target that isn't already open (showing "Opening
     * image …" in the milestone field) before executing, and if nothing is open at all, opens
     * it before the first instruction the same way.
     */
    private void stepForward() {
        if (playing || cursor >= rows.size()) return;
        List<TargetRow> checked = targetRows.stream().filter(r -> r.selected().get()).toList();
        if (checked.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Select a Target to execute the instructions.").showAndWait();
            return;
        }
        TargetRow target = checked.stream().filter(this::isTargetActive).findFirst().orElse(checked.get(0));
        if (cursor == 0 && activeBatch == null) resetRunCounters();
        setTransportEnabled(false);
        setRunningOverlay(true);
        boolean needsOpen = !isTargetActive(target);
        if (needsOpen) setMilestoneText("Opening image " + target.displayName());
        openTargetAndWait(target).thenRun(() -> Platform.runLater(() -> {
            if (needsOpen) resetMilestone();
            advanceOneRow().thenRun(() -> Platform.runLater(() -> {
                if (cursor >= rows.size()) exportTargetTrace();
                setTransportEnabled(true);
                setRunningOverlay(false);
                PlaylistRow last = rows.get(cursor - 1);
                boolean moreTargets = activeBatch != null && activeBatchIndex < activeBatch.size() - 1;
                if (last.result != null && last.result.status() == ReplayExecutor.Status.FAILED) {
                    setPhase(MiniPlayerStatus.Phase.FAILED,
                        last.result.errorMessage() != null ? last.result.errorMessage() : "failed");
                } else if (cursor >= rows.size() && !moreTargets) {
                    lastImageCount = activeBatch != null ? activeBatch.size() : 1;
                    activeBatch = null;
                    setPhase(MiniPlayerStatus.Phase.DONE, null);
                } else {
                    setPhase(MiniPlayerStatus.Phase.PAUSED, null);
                }
            }));
        }));
    }

    /** True if {@code target} is already the image open in QuPath — see {@link #openTargetAndWait}. */
    private boolean isTargetActive(TargetRow target) {
        if (target.entry() == null) return qupath.getImageData() != null;
        Project<BufferedImage> project = qupath.getProject();
        ImageData<BufferedImage> current = qupath.getImageData();
        ProjectImageEntry<BufferedImage> active = project != null && current != null ? project.getEntry(current) : null;
        return active != null && target.entry().equals(active);
    }

    /**
     * With no Target image(s) checked, behaves exactly as before this feature existed — plays
     * against whatever image is currently open. With at least one target checked, ▶ instead
     * drives {@link #runBatch} through all of them in turn; ▶│ (step-by-step) never triggers
     * this and stay scoped to whichever image happens to be active.
     */
    private void togglePlay() {
        if (playing) { playing = false; playBtn.setText("▶"); return; }
        if (cursor >= rows.size() && activeBatch == null) return;
        if (activeBatch != null) { resumeBatch(); return; }
        List<TargetRow> checked = new ArrayList<>(targetRows.stream().filter(r -> r.selected().get()).toList());
        if (checked.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Select a Target to execute the instructions.").showAndWait();
            return;
        }
        moveActiveImageFirst(checked);
        // Steps already run with ▶│ on the open image are not replayed — Play carries on from there.
        boolean continueHere = cursor > 0 && isTargetActive(checked.get(0));
        if (!continueHere) resetRunCounters();
        currentBatchTargetNames = checked.stream().map(TargetRow::displayName).toList();
        startPlayingUi();
        startMilestoneTracking(checked.size(), "Opening image " + checked.get(0).displayName());
        runBatch(checked, 0, !continueHere);
    }

    private void startPlayingUi() {
        playing = true;
        playBtn.setText("⏸");
        setTransportEnabled(false);
        playBtn.setDisable(false);
        setRunningOverlay(true);
        targetFilterToChecked = true; // hide unchecked targets — only the batch's own images stay visible
        rebuildTargetNodes();
        setPhase(MiniPlayerStatus.Phase.RUNNING, null);
    }

    /** ▶ after a pause or a failure pause: same batch, same image, from the next step. */
    private void resumeBatch() {
        List<TargetRow> targets = activeBatch;
        int index = activeBatchIndex;
        startPlayingUi();
        TargetRow target = targets.get(index);
        openTargetAndWait(target).thenRun(() -> Platform.runLater(() ->
            runPlayLoop(() -> runBatch(targets, index + 1, true))));
    }

    private void resetRunCounters() {
        runApplied = runFailed = runSkipped = runTraces = 0;
        runningRow = null;
    }

    /**
     * If a checked target is already the open image, moves it to the front of the batch — it
     * needs no {@link #openTargetAndWait}, so starting with it means Play begins immediately
     * instead of waiting on {@code openImageEntry} for whichever target happened to be first in
     * project order.
     */
    private void moveActiveImageFirst(List<TargetRow> checked) {
        Project<BufferedImage> project = qupath.getProject();
        ImageData<BufferedImage> current = qupath.getImageData();
        ProjectImageEntry<BufferedImage> active = project != null && current != null ? project.getEntry(current) : null;

        for (int i = 1; i < checked.size(); i++) {
            TargetRow row = checked.get(i);
            boolean isActive = row.entry() == null || (active != null && row.entry().equals(active));
            if (isActive) {
                checked.add(0, checked.remove(i));
                break;
            }
        }
    }

    /**
     * Plays through the current Instructions list, then either resets the transport (regular,
     * non-batch Play) or hands off to {@code onFinished} — {@link #runBatch} passes a callback
     * that opens the next target instead, so the UI stays "running" across the whole batch
     * rather than flashing back to idle between two images.
     */
    private void runPlayLoop(Runnable onFinished) {
        // Two different reasons can land here: the user paused mid-run (playing already false —
        // must stop the whole batch too, not just this image) vs. this image's steps simply ran
        // out (cursor >= rows.size() — the normal case, where a batch should move on to the next
        // target). Conflating them by unconditionally setting playing=false here was the actual
        // bug: runBatch()'s own guard checks `!playing` to decide whether to continue, so setting
        // it false before calling onFinished (== runBatch(targets, index+1)) made every batch stop
        // dead after its first image, every time — onFinished never got a chance to run past that
        // guard check.
        boolean userPaused = !playing;
        if (userPaused || cursor >= rows.size()) {
            Platform.runLater(() -> {
                if (!userPaused) exportTargetTrace();
                // A pause isn't the end of the run: the log stays open so ▶ can resume it.
                if (!userPaused) onRunFinished();
                if (onFinished != null && !userPaused) {
                    onFinished.run(); // batch continues — playing stays true across the handoff
                } else {
                    playing = false;
                    playBtn.setText("▶"); setTransportEnabled(true); setRunningOverlay(false);
                    // A pause mid-batch (onFinished != null but userPaused) also needs to drop
                    // the Target list back to "show everything" — runBatch() never gets a chance
                    // to do it itself since this branch is exactly what stops it being called again.
                    if (onFinished != null && userPaused) {
                        targetFilterToChecked = false; activeTargetRow = null; rebuildTargetNodes();
                        setPhase(MiniPlayerStatus.Phase.PAUSED, null);
                    }
                    // Only a genuine non-batch finish lands here — a batch leg finishing
                    // normally takes the onFinished.run() branch above instead, and runBatch()
                    // itself signals the batch's own true completion once every target is done.
                    if (onFinished == null && !userPaused) completeMilestone();
                }
            });
            return;
        }
        advanceOneRow().thenRun(() -> {
            PlaylistRow justRun = rows.get(cursor - 1);
            if (justRun.result != null && justRun.result.status() == ReplayExecutor.Status.FAILED) {
                // Window on screen: ask, as before. Mini-player only: pause on the failed step.
                Platform.runLater(() -> {
                    if (isWindowShowing()) confirmContinueAfterFailure(onFinished);
                    else pauseOnFailure(justRun);
                });
            } else {
                runPlayLoop(onFinished);
            }
        });
    }

    /** Stops on a failed step, keeping the batch: ▶ carries on with the next step. */
    private void pauseOnFailure(PlaylistRow failed) {
        playing = false;
        playBtn.setText("▶"); setTransportEnabled(true); setRunningOverlay(false);
        String msg = failed.result.errorMessage() != null ? failed.result.errorMessage() : "failed";
        setPhase(MiniPlayerStatus.Phase.FAILED, msg);
    }

    private void confirmContinueAfterFailure(Runnable onFinished) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
            "Step " + rows.get(cursor - 1).step.index() + " failed. Continue the replay?",
            ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("Step failed");
        alert.showAndWait().ifPresentOrElse(bt -> {
            if (bt == ButtonType.YES) {
                runPlayLoop(onFinished);
            } else {
                // "No" stops the whole batch too — onFinished deliberately not called, so a
                // batch never silently moves on to the next target after a declined failure.
                playing = false; playBtn.setText("▶"); setTransportEnabled(true); setRunningOverlay(false);
                targetFilterToChecked = false; activeTargetRow = null; rebuildTargetNodes();
                exportTargetTrace();
                onRunFinished();
                lastImageCount = currentBatchIndex != null ? currentBatchIndex : 1;
                activeBatch = null;
                setPhase(MiniPlayerStatus.Phase.DONE, null);
            }
        }, () -> {
            playing = false; playBtn.setText("▶"); setTransportEnabled(true); setRunningOverlay(false);
            targetFilterToChecked = false; activeTargetRow = null; rebuildTargetNodes();
        });
    }

    // ── Target image(s) batch orchestration ─────────────────────────────────

    /** @param resetCursor false only to carry on from steps already run (▶│) on the first target. */
    private void runBatch(List<TargetRow> targets, int index, boolean resetCursor) {
        boolean stoppedEarly = !playing;
        if (stoppedEarly || index >= targets.size()) {
            boolean fullyDone = !stoppedEarly; // reached here via exhausting targets, not a pause/decline
            if (fullyDone) { activeBatch = null; lastImageCount = targets.size(); }
            playing = false;
            currentTargetLabel = null;
            currentBatchTargetNames = null;
            currentBatchIndex = null;
            targetFilterToChecked = false; // batch is over — show the full project list again
            activeTargetRow = null;
            Platform.runLater(() -> {
                playBtn.setText("▶"); setTransportEnabled(true); setRunningOverlay(false);
                rebuildTargetNodes();
                if (fullyDone) completeMilestone();
            });
            return;
        }
        activeBatch = targets;
        activeBatchIndex = index;
        TargetRow target = targets.get(index);
        // Mark the handoff in the image we're leaving's own log — its own file, not just the
        // on-screen Console, is what still says "we moved on to X next" after the window closes.
        if (logWriter != null) {
            try { logWriter.appendBatchTransition(target.displayName()); } catch (Exception ignored) {}
        }
        currentTargetLabel = target.displayName();
        currentBatchIndex = index + 1;
        activeTargetRow = target;
        rebuildTargetNodes();
        setMilestoneText("Opening image " + target.displayName());
        openTargetAndWait(target).thenRun(() -> Platform.runLater(() -> {
            if (resetCursor) cursor = 0;
            refreshPacketStrip();
            logArea.appendText("=== Target " + (index + 1) + "/" + targets.size() + ": " + target.displayName() + " ===\n");
            setMilestoneText("Running instructions on " + target.displayName());
            setPhase(MiniPlayerStatus.Phase.RUNNING, null);
            runPlayLoop(() -> runBatch(targets, index + 1, true));
        }));
    }

    /**
     * Opens {@code target} and waits until it's really loaded, or resolves immediately if it's
     * already the active image (the no-project fallback row, or re-selecting the same target
     * twice in a row). Mirrors {@link QTraceBatchExporter}'s own open-and-poll pattern: fire
     * {@code openImageEntry} on the FX thread, then poll {@link ActionLogger#getCurrentImageData()}
     * / {@link ActionLogger#getImageHash()} on a background thread — that's qTrace's own signal
     * that the image is attached and hashed, not just that the viewer swapped.
     */
    private CompletableFuture<Void> openTargetAndWait(TargetRow target) {
        if (target.entry() == null) return CompletableFuture.completedFuture(null);
        Project<BufferedImage> project = qupath.getProject();
        ImageData<BufferedImage> current = qupath.getImageData();
        ProjectImageEntry<BufferedImage> active = project != null && current != null ? project.getEntry(current) : null;
        if (target.entry().equals(active)) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> future = new CompletableFuture<>();
        // A never-opened image has no history of its own: its .qtrace must hold only the replay.
        pendingKeepHistory = target.entry().hasImageData();
        Platform.runLater(() -> QTraceController.openEntryOn(qupath, target.entry()));
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 180_000; // generous — mirrors QTraceBatchExporter's 5 min
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { break; }
                ImageData<BufferedImage> data = logger.getCurrentImageData();
                if (data != null && logger.getImageHash() != null
                    && project != null && target.entry().equals(project.getEntry(data))) break;
            }
            future.complete(null);
        }, "qtrace-target-open-wait").start();
        return future;
    }

    /** Executes (or skips) the row at {@code cursor}, advances it, and logs the outcome. */
    private java.util.concurrent.CompletableFuture<Void> advanceOneRow() {
        PlaylistRow row = rows.get(cursor);
        if (!row.step.isExecutable()) {
            // Non-executable row (e.g. a Measurement Map view) — there's no groovyFragment to
            // run, so it never reaches the executor at all: no startNewRun(), no StepResult, no
            // log entry. Its status label (see rebuildPlaylistNodes()) is a fixed "Not replayable"
            // message, not execution-driven, so it must be left alone here.
            cursor++;
            highlightRow(row);
            scrollToRow(row);
            bumpMilestoneProgress();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        // A fresh run starts at row 0 normally, but a click can also reposition the cursor
        // mid-list — guard against acting with no executor/log yet in that case too. Jumping
        // in without having run the earlier steps in this session can still fail on undefined
        // variables (fragments share one Binding) — that's a script-continuity
        // limitation, not something this guard is meant to paper over.
        // (The run's first row is the first one that can run: greyed rows the author took out
        // of the replay may come before it.)
        if (executor == null || row == firstExecutableRow()) startNewRun();
        cursor++;

        runningRow = row;
        if (row.included.get()) setPhase(MiniPlayerStatus.Phase.RUNNING, null);
        ReplayRecorder rec = ensureRecorder();
        java.util.concurrent.CompletableFuture<Void> ready = java.util.concurrent.CompletableFuture.completedFuture(null);
        if (!preambleRun) {
            preambleRun = true;
            if (rec != null) rec.beforePreamble();
            ready = executor.executePreamble(preambleRunPreambleText)
                .thenAccept(preambleResult -> { if (rec != null) rec.afterPreamble(); });
        }
        java.util.concurrent.CompletableFuture<ReplayExecutor.StepResult> future = ready.thenCompose(v -> {
            if (!row.included.get())
                return java.util.concurrent.CompletableFuture.completedFuture(skippedResult(row.flaggedIncompatible));
            if (rec != null) rec.beforeStep(row.step);
            traceExportPending = true;
            return executor.executeStep(row.step).thenApply(result -> {
                if (rec != null) rec.afterStep(row.step, result.status());
                return result;
            });
        });

        return future.thenAccept(result -> Platform.runLater(() -> applyResult(row, result)));
    }

    /**
     * The recorder for the image the next instruction will run on — a new one (and a new
     * replay start on that image's ActionLogger) whenever the replay has moved to another
     * image, or qTrace re-attached to it. Null if no image is open.
     */
    private ReplayRecorder ensureRecorder() {
        ImageData<BufferedImage> current = logger.getCurrentImageData();
        if (current == null) return null;
        if (recorder == null || current != recorderImage || logger.getReplayedFrom() == null) {
            // Not opened by the replay (already on screen): keep what's there, it may be unsaved work.
            boolean keepHistory = pendingKeepHistory == null || pendingKeepHistory;
            pendingKeepHistory = null;
            recorder = new ReplayRecorder(logger, current::getHistoryWorkflow);
            recorder.start(replayedFrom(), keepHistory);
            recorderImage = current;
            traceExportPending = false;
        }
        return recorder;
    }

    private com.google.gson.JsonObject replayedFrom() {
        com.google.gson.JsonObject from = new com.google.gson.JsonObject();
        from.addProperty("source", qtraceBaseName);
        from.addProperty("sha256", sourceQtraceHash);
        String certId = replaySource != null ? QTraceReplayEngine.extractCertId(replaySource) : null;
        if (certId != null && certId.startsWith("qtc_")) from.addProperty("cert_id", certId);
        from.addProperty("replayed_at", java.time.Instant.now().toString());
        if (workflowLoaded && loadedQtraceJson != null) WorkflowSource.describe(from, loadedQtraceJson, new ArrayList<>(selectedPackets));
        return from;
    }

    /**
     * Writes the current target's .qtrace once its run is over — every target of a batch gets
     * its own, holding exactly the instructions that ran OK on it (see ReplayRecorder).
     * FX thread.
     */
    private void exportTargetTrace() {
        if (!traceExportPending || recorderImage == null || logger.getCurrentImageData() != recorderImage) return;
        traceExportPending = false;
        try {
            java.nio.file.Path out = QTraceController.exportUnstamped(qupath, logger);
            runTraces++;
            logArea.appendText("=== .qtrace written for this image: " + out + " ===\n");
        } catch (Exception e) {
            logArea.appendText("(.qtrace export failed: " + e.getMessage() + ")\n");
        }
    }

    private ReplayExecutor.StepResult skippedResult(boolean incompatible) {
        return new ReplayExecutor.StepResult(
            incompatible ? ReplayExecutor.Status.SKIPPED_INCOMPATIBLE : ReplayExecutor.Status.SKIPPED_EXCLUDED,
            null, 0, null);
    }

    private void applyResult(PlaylistRow row, ReplayExecutor.StepResult result) {
        row.result = result;
        if (row.packet >= 0 || sessionStrip) refreshPacketStrip();
        switch (result.status()) {
            case OK -> runApplied++;
            case FAILED -> runFailed++;
            case SKIPPED_EXCLUDED, SKIPPED_INCOMPATIBLE -> runSkipped++;
        }
        fireState();
        RowView rv = rowViews.get(row);
        if (rv != null) {
            switch (result.status()) {
                case OK -> {
                    rv.statusLabel.setText("✅ Execution completed");
                    rv.statusLabel.setTextFill(Color.web(GREEN));
                    rv.timerLabel.setText(formatDuration(result.durationMs()));
                    rv.timerLabel.setTextFill(Color.web(TEXT_SUB));
                }
                case FAILED -> {
                    rv.node.setCursor(javafx.scene.Cursor.HAND); // clickable: opens the full error
                    rv.statusLabel.setText("❌ " + (result.errorMessage() != null ? result.errorMessage() : "failed"));
                    rv.statusLabel.setTextFill(Color.web(RED));
                    Tooltip.install(rv.statusLabel, new Tooltip("Click the row to see the full error"));
                    rv.timerLabel.setText(formatDuration(result.durationMs()));
                    rv.timerLabel.setTextFill(Color.web(TEXT_SUB));
                }
                case SKIPPED_INCOMPATIBLE -> { rv.statusLabel.setText("Skipped (incompatible)"); rv.statusLabel.setTextFill(Color.web(PEACH)); }
                case SKIPPED_EXCLUDED -> { rv.statusLabel.setText("Skipped (unchecked)"); rv.statusLabel.setTextFill(Color.web(MUTED)); }
            }
        }
        highlightRow(row);
        scrollToRow(row);

        String line = String.format("[Step %d] %-16s %-30s %s (%dms)",
            row.step.index(), row.step.type(), row.step.title(), result.status(), result.durationMs());
        logArea.appendText(line + "\n");

        try {
            if (logWriter != null) logWriter.appendStep(row.step, result);
        } catch (Exception e) {
            logArea.appendText("(log write failed: " + e.getMessage() + ")\n");
        }

        bumpMilestoneProgress();
    }

    /**
     * e.g. 4837ms -> "00:04" — MM:SS, since a segmentation step can run 10+ minutes.
     * Below 5s, MM:SS would round down to noise ("00:03" for both a 3.1s and 3.9s step) — shown
     * in milliseconds instead, e.g. "3120ms".
     */
    private static String formatDuration(long millis) {
        if (millis < 5000) return millis + "ms";
        long totalSeconds = millis / 1000;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    // ── Milestone info ───────────────────────────────────────────────────────

    /** Called before Play starts — {@code imageCount} is 1 outside a Target image(s) batch. */
    private void startMilestoneTracking(int imageCount, String initialText) {
        totalWorkUnits = rows.size() * Math.max(1, imageCount);
        completedWorkUnits = 0;
        setMilestoneText(initialText);
    }

    private void setMilestoneText(String text) {
        currentMilestoneText = text;
        Platform.runLater(this::refreshMilestoneLabel);
    }

    private void bumpMilestoneProgress() {
        completedWorkUnits++;
        Platform.runLater(this::refreshMilestoneLabel);
    }

    private void refreshMilestoneLabel() {
        int pct = totalWorkUnits > 0 ? Math.min(100, (completedWorkUnits * 100) / totalWorkUnits) : 0;
        milestoneLabel.setTextFill(Color.web(MUTED));
        milestoneLabel.setStyle("-fx-font-weight: normal;");
        milestoneLabel.setText(currentMilestoneText + "  —  " + pct + "%");
    }

    /** Only called on a genuine end-to-end finish (not a pause, not a declined failure). */
    private void completeMilestone() {
        completedWorkUnits = totalWorkUnits;
        setPhase(MiniPlayerStatus.Phase.DONE, null);
        Platform.runLater(() -> {
            milestoneLabel.setTextFill(Color.web(GREEN));
            milestoneLabel.setStyle("-fx-font-weight: bold;");
            milestoneLabel.setText("Execution completed");
        });
    }

    private void resetMilestone() {
        totalWorkUnits = 0;
        completedWorkUnits = 0;
        currentMilestoneText = "Idle";
        refreshMilestoneLabel();
    }

    // ── Run lifecycle ────────────────────────────────────────────────────────

    /**
     * Starts a fresh run: a new {@link ReplayExecutor} (new GroovyShell/Binding — a
     * previous run's variables must not leak into this one), a new {@link ReplayLogWriter}
     * (one file per execution, never appended across runs), and a reset Stamper button.
     * Triggered whenever the row about to execute is the playlist's first row — covers a
     * fresh load, a click back on the first row, and pressing Play again after a
     * previous run completed.
     */
    private PlaylistRow firstExecutableRow() {
        for (PlaylistRow r : rows) if (r.step.isExecutable()) return r;
        return null;
    }

    /**
     * The classloader a replayed script's fragments are compiled against.
     *
     * QuPath 0.7.x loads every extension — built-in ones like the ImageJ bridge
     * ({@code qupath.imagej.*}) and OpenCV pixel-classification ops
     * ({@code qupath.opencv.ops.*}, {@code qupath.opencv.ml.pixel.*}), the fxtras dialogs
     * ({@code qupath.fx.dialogs.*}), third-party ones (StarDist, ...) — into its own
     * {@code ExtensionClassLoader}, via {@code QuPathGUI.getExtensionCatalogManager()}. That
     * classloader is neither an ancestor nor a descendant of qtrace's own module classloader or
     * of {@code QuPathGUI.class.getClassLoader()}: a script importing any of those classes
     * resolves fine in QuPath's own Script Editor (which uses the extension classloader) but
     * fails to compile here unless we use the same one.
     *
     * {@code getExtensionCatalogManager()} doesn't exist on QuPath 0.5.x (no extension manager
     * yet, compile target here) — reflection, with the GUI's own classloader as the fallback,
     * which already has everything a 0.5.x install needs.
     */
    private ClassLoader replayScriptClassLoader() {
        try {
            Object manager = QuPathGUI.class.getMethod("getExtensionCatalogManager").invoke(qupath);
            Object cl = manager.getClass().getMethod("getExtensionClassLoader").invoke(manager);
            if (cl instanceof ClassLoader c) return c;
        } catch (Exception ignored) {}
        return qupath.getClass().getClassLoader();
    }

    private void startNewRun() {
        if (logWriter != null) {
            try { logWriter.close(); } catch (Exception ignored) {}
        }
        if (executor != null) executor.close();
        executor = new ReplayExecutor(replayScriptClassLoader(),
            line -> Platform.runLater(() -> logArea.appendText(line + "\n")));
        if (qtraceSourceDir != null) executor.setVariable("_qtraceSourceDir", qtraceSourceDir);
        preambleRun = false;
        pendingStamp = null;
        stampBtn.setDisable(true);
        applyGrayStamp();

        try {
            List<Integer> excluded = rows.stream()
                .filter(r -> !r.included.get())
                .map(r -> r.step.index())
                .collect(Collectors.toList());
            String qupathVersion = qupath.getVersion() != null ? qupath.getVersion().toString() : "unknown";
            String compatSummary = infoLabel.getText();
            logWriter = new ReplayLogWriter(qtraceBaseName, sourceQtraceHash, qupathVersion, compatSummary, excluded,
                currentTargetLabel, currentBatchTargetNames, currentBatchIndex, resolveLogsDir());
            logArea.appendText("=== New run — log: " + logWriter.logPath() + " ===\n");
            setLogsButtonState(true);
        } catch (Exception e) {
            logWriter = null;
            logArea.appendText("(could not open log file: " + e.getMessage() + ")\n");
        }
    }

    // ── End of run ───────────────────────────────────────────────────────────

    private void onRunFinished() {
        if (logWriter == null) return; // log file failed to open at run start — nothing to finalize
        try {
            String qupathVersion = qupath.getVersion() != null ? qupath.getVersion().toString() : "unknown";
            String replayer = QTracePluginManager.isEntitled()
                ? QTracePluginManager.getEntitled().getActiveLicenseInfo().name()
                : System.getProperty("user.name", "unknown");
            pendingStamp = logWriter.finishUnsigned(replayer, sourceQtraceHash, qupathVersion);
            logArea.appendText("\n=== Run finished — " + pendingStamp.resultStatus()
                + " (" + pendingStamp.stepsFailed() + " error(s)) — log: " + logWriter.logPath() + " ===\n");
            stampBtn.setDisable(false);
        } catch (Exception e) {
            logArea.appendText("(finalize failed: " + e.getMessage() + ")\n");
        }
    }

    /**
     * Assembles the script for whatever rows are currently checked in the playlist — same
     * preamble text the run itself evaluated ({@link #preambleRunPreambleText}) plus each
     * included row's step, in playlist order — and opens it in QuPath's own Script Editor.
     */
    private void exportCode() {
        if (rows.isEmpty() || preambleRunPreambleText == null) {
            new Alert(Alert.AlertType.INFORMATION, "Load a .qtrace first.").showAndWait();
            return;
        }
        List<ReplayStep> selected = rows.stream()
            .filter(r -> r.included.get())
            .map(r -> r.step)
            .collect(Collectors.toList());
        if (selected.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No instruction checked in the playlist to export.").showAndWait();
            return;
        }
        String script = QTraceReplayEngine.assembleScript(preambleRunPreambleText, selected);
        QTraceController.openInScriptEditor(qupath, script);
    }

    private void onStamp() {
        if (pendingStamp == null) return;
        QTracePlugin ep = QTracePluginManager.getEntitled();
        if (ep != null && ep.hasEncryptedSigningKey() && ep.getDecryptedSigningKey() == null) {
            ep.promptPassphraseAndDecrypt(stage);
            if (ep.getDecryptedSigningKey() == null) return; // cancelled or wrong passphrase
        }
        try {
            ReplayStamp signed = logWriter.appendSignedStamp(pendingStamp);
            if (signed != null) {
                applyGoldStamp();
                stampBtn.setDisable(true);
                logArea.appendText("🏅 Stamped by " + signed.replayer() + "\n");
            } else {
                logArea.appendText("(no signing key available — log left unsigned)\n");
            }
        } catch (Exception e) {
            logArea.appendText("(stamp failed: " + e.getMessage() + ")\n");
        }
    }

    // ── Small UI helpers ─────────────────────────────────────────────────────

    /**
     * Drives every "a run is active" signal: the small spinner in the Console footer (visible
     * only while running) and the disabled state of the Instructions/Target checkbox columns
     * (bound to runningProperty — see rebuildPlaylistNodes()/rebuildTargetNodes()). No longer an
     * overlay over Instructions — see buildPlaylistSection().
     */
    private void setRunningOverlay(boolean running) {
        runningProperty.set(running);
        runningSpinner.setVisible(running);
        runningSpinner.setManaged(running);
    }

    private void setTransportEnabled(boolean enabled) {
        playBtn.setDisable(!enabled);
        nextBtn.setDisable(!enabled);
    }

    private Button transportButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button b = new Button(text);
        b.setFont(Font.font("System", FontWeight.BOLD, 12));
        b.setTextFill(Color.web(TEXT));
        b.setPrefWidth(38);
        b.setStyle("-fx-background-color: " + SURFACE + "; -fx-background-radius: 3; -fx-cursor: hand;");
        b.setOnAction(action);
        return b;
    }

    private void applyGrayStamp() {
        stampBtn.setStyle(
            "-fx-background-color: " + BG + "; -fx-border-color: " + BORDER + "; -fx-border-radius: 3;"
          + "-fx-background-radius: 3; -fx-text-fill: " + MUTED + "; -fx-cursor: hand; -fx-padding: 5 10 5 10;");
    }

    private void applyGoldStamp() {
        stampBtn.setStyle(
            "-fx-background-color: " + GOLD_BG + "; -fx-border-color: #4a3c1e; -fx-border-radius: 3;"
          + "-fx-background-radius: 3; -fx-text-fill: " + GOLD + "; -fx-cursor: default; -fx-padding: 5 10 5 10;");
    }

    /** Matches the Dashboard's own "Refresh" button style exactly — dark text on solid color, darkening on hover. */
    private void styleButton(Button b, String color) {
        b.setFont(Font.font("System", FontWeight.BOLD, 11));
        String base  = "-fx-background-color: " + color + "; -fx-text-fill: " + BG + ";"
            + "-fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 6 12 6 12;";
        String hover = "-fx-background-color: derive(" + color + ", -15%); -fx-text-fill: " + BG + ";"
            + "-fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 6 12 6 12;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(hover));
        b.setOnMouseExited(e -> b.setStyle(base));
    }

    private static String fieldStyle() {
        return "-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";"
             + "-fx-prompt-text-fill: " + MUTED + "; -fx-border-color: " + BORDER + ";"
             + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 6 8 6 8;";
    }

    private static Label label(String text, String color, int size, FontWeight weight) {
        Label l = new Label(text);
        l.setTextFill(Color.web(color));
        l.setFont(Font.font("System", weight, size));
        return l;
    }

    private static String baseNameOf(String source) {
        String name = source.contains("/") ? source.substring(source.lastIndexOf('/') + 1) : source;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ── Row model ────────────────────────────────────────────────────────────

    private static class PlaylistRow {
        final ReplayStep step;
        final boolean flaggedIncompatible;
        final SimpleBooleanProperty included = new SimpleBooleanProperty(true);
        ReplayExecutor.StepResult result;
        int packet = -1;   // a workflow's instruction: the packet it belongs to; -1 for a record
        boolean expanded;  // its script is shown under the row (the eye)

        PlaylistRow(ReplayStep step, boolean flaggedIncompatible) {
            this.step = step;
            this.flaggedIncompatible = flaggedIncompatible;
            // Non-executable rows (e.g. a Measurement Map view — see ReplayStep#isExecutable)
            // never get checked: there's no groovyFragment for the executor to run.
            if (flaggedIncompatible || !step.isExecutable()) included.set(false);
        }
    }

    /** One playlist row's live UI node — kept so status updates and highlighting don't need a full rebuild. */
    private static class RowView {
        final HBox node;
        final Label statusLabel;
        final Label timerLabel;
        final String baseStyle;

        RowView(HBox node, Label statusLabel, Label timerLabel, String baseStyle) {
            this.node = node;
            this.statusLabel = statusLabel;
            this.timerLabel = timerLabel;
            this.baseStyle = baseStyle;
        }
    }
}
