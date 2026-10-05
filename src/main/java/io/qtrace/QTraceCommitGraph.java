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

import com.google.gson.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Commit-graph window — visualizes a .qtrace as a git-like history (v1: linear "main" branch).
 *
 * Each session is one commit, drawn left→right and chained by {@code parent_session_id}.
 * A commit has exactly one author (the contributor who exported/stamped it): one badge per node.
 * Node title = stamp notes (→ scope → "Session #n"). The side panel answers "who did what":
 * the contributor and the categorized actions of that commit (from the {@code contributions}
 * block, which already excludes pre-tracking steps inherited from a prior contributor).
 *
 * Below the graph, a vertical step timeline (git-graph style, model in {@link VersionTimeline})
 * lists every captured action as a grey dot, each session closing on its stamp milestone —
 * a dot coloured by confidence, or a hollow dashed one when the session was never stamped.
 *
 * Compliance-only feature (opened from the panel), but lives in Core as it only reads JSON.
 */
public class QTraceCommitGraph {

    // ── Catppuccin Mocha (matches QTraceDashboard / QTracePanel) ───────────────
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BG_CARD    = "#24273a";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";
    private static final String PEACH      = "#fab387";
    private static final String RED        = "#f38ba8";
    private static final String MAUVE      = "#cba6f7";   // a workflow's packets: neither stamped nor unstamped

    // ── Layout constants ───────────────────────────────────────────────────────
    private static final double X_GAP   = 190;
    private static final double ORIGIN_X = 90;
    private static final double LANE_Y   = 130;
    private static final double NODE_R   = 20;

    // ── Node model ─────────────────────────────────────────────────────────────
    private static final class Node {
        String  id, parentId, branch;
        String  contributor, validator, confidence, fidelity, notes, scope, exportedAt, imageHashShort;
        boolean signed;
        boolean stamped = true;     // false: autosaved session, never validated
        boolean live;               // the open image's capture, not in the .qtrace yet
        boolean covered;            // unstamped, but a later stamp validated it: frozen like a stamped one
        int     stepsCaptured, preTracking;
        JsonObject contributions;   // { contributor, actions:{cat:count} }
        double  cx, cy;             // canvas centre (for hit-testing)
        int     index;
        // A workflow's packet (VersionEditor): its notes, the stamp facts of the session it comes
        // from (null for a packet created in the workflow), what it holds.
        boolean packet;
        String  packetNotes;
        JsonObject origin;
        int     steps, sideRecords;
    }

    /**
     * The open image, as far as this window is concerned. Without a host (screenshot harness)
     * the window only reads the file: no "in progress" session, no −/+ on the steps.
     */
    public interface Host {
        /**
         * The capture in progress of the image {@code qtrace} belongs to ({@code null}: the open
         * image has no .qtrace yet), as a session flagged {@code "live"} — or null when that
         * image is not the one open, or nothing was captured.
         */
        JsonObject liveSession(File qtrace);

        /** The .qtrace of the image open in QuPath, or null when it has none yet (or no image is open). */
        default File currentRecord() { return null; }

        /** Opens the replay player on a source (a qtw_… ID, …); false when there is no player to open. */
        default boolean playInPlayer(String source) { return false; }

        /** Takes an instruction out of the replay or puts it back, wherever it is still unstamped. */
        void setReplaySkip(File qtrace, String fragment, boolean skip) throws java.io.IOException;
    }

    private final QuPathGUI qupath;
    private Host host;
    private File loadedFile;
    private final Stage     stage;
    private final Canvas    canvas;
    private final VBox      detailBox;
    private final Label     headerLabel;
    private final List<Node> nodes = new ArrayList<>();

    // Step timeline (below the graph).
    private static final String MONO = "Monospaced";
    private static final String ROW_IDLE = "-fx-border-color: transparent; -fx-border-width: 0 0 0 3;";
    private static final String ROW_SELECTED = "-fx-background-color: rgba(250,179,135,0.12);"
        + "-fx-border-color: " + PEACH + "; -fx-border-width: 0 0 0 3;";
    // While a packet or an instruction is dragged over a row: where it would land.
    private static final String ROW_DROP_BEFORE = "-fx-border-color: " + MAUVE + " transparent transparent transparent;"
        + "-fx-border-width: 2 0 0 3;";
    private static final String ROW_DROP_AFTER = "-fx-border-color: transparent transparent " + MAUVE + " transparent;"
        + "-fx-border-width: 0 0 2 3;";
    private static final String DRAG_PREFIX = "qtrace-workflow:";
    // Virtualized: real traces carry ~900 steps; one node tree per row made every click relayout
    // them all (~1 s per click). A ListView only builds the rows on screen.
    private final ListView<VersionTimeline.Entry> timelineList;
    private final Label      timelineFooter;
    private final Map<Integer, Integer> milestoneIndex = new HashMap<>();   // session → list index
    private List<VersionTimeline.Entry> allEntries = List.of();
    private JsonObject timelineRoot;
    private Integer shownSession;   // node clicked in the graph: only its steps are listed; null = all

    // Editing mode, added by a module (VersionEditors): the window shows the workflow being
    // composed from the record instead of the record, which is never written. Null outside it.
    private VersionEditor.Document editing;
    private boolean editingDirty;
    private String  editingStatus = "";
    private final Button modeBtn, recordBtn, addPacketBtn, mergeBtn, saveBtn, publishBtn;
    // Console of the editing mode, under the list: what was saved, published, refused. It can be folded.
    private final VBox consoleBox;
    private final javafx.scene.control.TextArea consoleArea;
    private final Button consoleToggle, consolePlayBtn;
    private String lastPublishedId;
    // "Record": what the user does in QuPath goes into one packet, named by its id (it can be
    // moved meanwhile). Null when not recording.
    private WorkflowRecording recording;
    private String recordingPacketId;
    // Packets picked in the graph (click, Ctrl+click, Shift+click) — several can be merged.
    private final java.util.TreeSet<Integer> pickedPackets = new java.util.TreeSet<>();
    private int lastPicked = -1;
    private final Label  banner;

    public QTraceCommitGraph(QuPathGUI qupath) {
        this.qupath = qupath;
        this.stage  = new Stage();
        stage.setTitle(QTraceI18n.t("graph.window.title"));
        // setWidth()/setHeight() weren't honored on first show() no matter when they were
        // called (window opened at JavaFX's 200×200 default) — only setMinWidth/setMinHeight
        // reliably applied, so those carry the real target size instead.
        stage.setMinWidth(1180);
        stage.setMinHeight(760);

        headerLabel = new Label("");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
        headerLabel.setTextFill(Color.web(TEXT_MAIN));

        Button openBtn = new Button(QTraceI18n.t("graph.open"));
        openBtn.setId("graph-open-button"); // looked up by the screenshot harness — see ScreenshotHarness
        styleButton(openBtn);
        openBtn.setOnAction(e -> chooseFile());

        modeBtn = new Button();
        styleButton(modeBtn);
        modeBtn.setOnAction(e -> toggleEditing());
        recordBtn = new Button();
        styleButton(recordBtn);
        recordBtn.setOnAction(e -> toggleRecording());
        addPacketBtn = new Button(QTraceI18n.t("graph.edit.packet.add"));
        styleButton(addPacketBtn);
        addPacketBtn.setOnAction(e -> edit(() -> editing.addPacket(nodes.size(), null), -1));
        mergeBtn = new Button();
        styleButton(mergeBtn);
        mergeBtn.setTooltip(new Tooltip(QTraceI18n.t("graph.edit.merge.tip")));
        mergeBtn.setOnAction(e -> mergePicked());
        saveBtn = new Button(QTraceI18n.t("graph.edit.save"));
        styleButton(saveBtn);
        saveBtn.setOnAction(e -> saveWorkflow());
        publishBtn = new Button(QTraceI18n.t("graph.edit.publish"));
        styleButton(publishBtn);
        publishBtn.setTooltip(new Tooltip(QTraceI18n.t("graph.edit.publish.tip")));
        publishBtn.setOnAction(e -> publishWorkflow());

        banner = new Label(QTraceI18n.t("graph.edit.banner"));
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setPadding(new Insets(6, 14, 6, 14));
        banner.setFont(Font.font("System", FontWeight.BOLD, 11));
        banner.setStyle("-fx-background-color: " + MAUVE + "; -fx-text-fill: " + BG_BASE + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, headerLabel, spacer, recordBtn, addPacketBtn, mergeBtn, saveBtn, publishBtn, modeBtn, openBtn);
        header.setId("graph-header"); // looked up by the screenshot harness — see ScreenshotHarness
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));
        header.setStyle("-fx-background-color: " + BG_CARD + ";");

        canvas = new Canvas(900, 230);
        Pane canvasPane = new Pane(canvas);
        canvasPane.setStyle("-fx-background-color: " + BG_BASE + ";");
        ScrollPane scroll = new ScrollPane(canvasPane);
        scroll.setId("graph-canvas"); // looked up by the screenshot harness — see ScreenshotHarness
        scroll.setStyle("-fx-background: " + BG_BASE + "; -fx-background-color: " + BG_BASE + ";");
        scroll.setFitToHeight(true);

        detailBox = new VBox(6);
        detailBox.setId("graph-detail-panel"); // looked up by the screenshot harness — see ScreenshotHarness
        detailBox.setPadding(new Insets(12));
        detailBox.setPrefWidth(280);
        detailBox.setStyle("-fx-background-color: " + BG_SURFACE + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 0 1;");
        showEmptyDetail();

        // A node limits the timeline to its session; a click beside the nodes lists everything again.
        canvas.setOnMouseClicked(e -> {
            Node hit = nodeAt(e.getX(), e.getY());
            // Editing mode: Ctrl or Shift adds packets to the ones picked, to merge them.
            if (editing != null && hit != null && (e.isShortcutDown() || e.isShiftDown())) {
                pickPacket(hit.index, e.isShiftDown());
                return;
            }
            pickedPackets.clear();
            if (editing != null && hit != null) pickedPackets.add(hit.index);
            lastPicked = hit != null ? hit.index : -1;
            updateModeControls();
            shownSession = hit != null ? Integer.valueOf(hit.index) : null;
            showTimeline();
            if (hit != null) selectCommit(hit);
            else { redraw(null); showEmptyDetail(); }
        });

        Label timelineTitle = new Label(QTraceI18n.t("graph.timeline.title"));
        timelineTitle.setTextFill(Color.web(BLUE));
        timelineTitle.setFont(Font.font("System", FontWeight.BOLD, 12));
        timelineTitle.setPadding(new Insets(8, 14, 6, 14));

        timelineList = new ListView<>();
        timelineList.setId("graph-timeline"); // looked up by the screenshot harness — see ScreenshotHarness
        timelineList.setStyle("-fx-background-color: " + BG_BASE + "; -fx-control-inner-background: " + BG_BASE + ";"
            + "-fx-background-insets: 0; -fx-padding: 0;");
        timelineList.setCellFactory(lv -> new TimelineCell());
        Label placeholder = muted(QTraceI18n.t("graph.timeline.empty"));
        fill(placeholder, Color.web(TEXT_MUTED));
        timelineList.setPlaceholder(placeholder);
        // Editing mode: several instructions can be selected (Ctrl, Shift), removed or moved together.
        timelineList.getSelectionModel().getSelectedItems().addListener(
            (javafx.collections.ListChangeListener<VersionTimeline.Entry>) c -> {
                if (editing != null && selectedSteps().size() > 1) showStepsPickedDetail();
            });
        timelineList.setOnContextMenuRequested(e -> {
            List<VersionEditor.Step> picked = selectedSteps();
            if (editing == null || picked.isEmpty()) return;
            javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem(
                QTraceI18n.f("graph.edit.steps.remove", picked.size()));
            remove.setOnAction(ev -> removeSelectedSteps());
            new javafx.scene.control.ContextMenu(remove).show(timelineList, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        timelineList.setOnKeyPressed(e -> {
            if (editing != null && (e.getCode() == javafx.scene.input.KeyCode.DELETE || e.getCode() == javafx.scene.input.KeyCode.BACK_SPACE)) {
                removeSelectedSteps();
                e.consume();
            }
        });
        timelineList.getSelectionModel().selectedItemProperty().addListener((o, was, e) -> {
            if (editing != null && selectedSteps().size() > 1) return;   // the group has its own panel
            if (e == null || e.sessionIndex() >= nodes.size()) return;
            Node n = nodes.get(e.sessionIndex());
            if (e.kind() == VersionTimeline.Kind.STEP) showStepDetail(e, n); else showDetail(n);
            redraw(n);
        });
        // A drag near the top or the bottom of the list scrolls it: a workflow is longer than the window.
        timelineList.addEventFilter(javafx.scene.input.DragEvent.DRAG_OVER, e -> {
            if (editing == null || dragOf(e.getDragboard()) == null) return;
            if (timelineList.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
                if (e.getY() < 28) flow.scrollPixels(-14);
                else if (e.getY() > timelineList.getHeight() - 28) flow.scrollPixels(14);
            }
        });
        VBox.setVgrow(timelineList, Priority.ALWAYS);

        timelineFooter = new Label("");
        timelineFooter.setFont(Font.font(MONO, 10));
        timelineFooter.setTextFill(Color.web(TEXT_MUTED));
        timelineFooter.setMaxWidth(Double.MAX_VALUE);
        timelineFooter.setPadding(new Insets(6, 14, 6, 14));
        timelineFooter.setStyle("-fx-background-color: " + BG_SURFACE + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 1 0 0 0;");

        consoleArea = new javafx.scene.control.TextArea();
        consoleArea.setEditable(false);
        consoleArea.setWrapText(true);
        consoleArea.setPrefRowCount(6);
        consoleArea.setStyle("-fx-control-inner-background: " + BG_SURFACE + "; -fx-text-fill: " + TEXT_SUB + ";"
            + "-fx-font-family: '" + MONO + "'; -fx-font-size: 11; -fx-background-insets: 0; -fx-focus-color: transparent;"
            + "-fx-faint-focus-color: transparent;");
        consoleToggle = new Button();
        consoleToggle.setStyle("-fx-background-color: transparent; -fx-text-fill: " + BLUE + "; -fx-font-weight: bold;"
            + "-fx-font-size: 12; -fx-cursor: hand; -fx-padding: 4 0 4 0;");
        consoleToggle.setOnAction(e -> { show(consoleArea, !consoleArea.isVisible()); updateConsoleControls(); });
        consolePlayBtn = new Button(QTraceI18n.t("graph.edit.console.play"));
        styleButton(consolePlayBtn);
        consolePlayBtn.setOnAction(e -> playPublished());
        Button consoleCopy = new Button(QTraceI18n.t("graph.edit.console.copy"));
        styleButton(consoleCopy);
        consoleCopy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(consoleArea.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });
        Button consoleClear = new Button(QTraceI18n.t("graph.edit.console.clear"));
        styleButton(consoleClear);
        consoleClear.setOnAction(e -> consoleArea.clear());
        Region consoleSpacer = new Region();
        HBox.setHgrow(consoleSpacer, Priority.ALWAYS);
        HBox consoleHead = new HBox(8, consoleToggle, consoleSpacer, consolePlayBtn, consoleCopy, consoleClear);
        consoleHead.setAlignment(Pos.CENTER_LEFT);
        consoleHead.setPadding(new Insets(2, 14, 2, 14));
        consoleBox = new VBox(consoleHead, consoleArea);
        consoleBox.setStyle("-fx-background-color: " + BG_BASE + "; -fx-border-color: " + BORDER + "; -fx-border-width: 1 0 0 0;");
        updateConsoleControls();

        VBox timelinePane = new VBox(timelineTitle, timelineList, timelineFooter, consoleBox);
        timelinePane.setStyle("-fx-background-color: " + BG_BASE + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 1 0 0 0;");

        SplitPane split = new SplitPane(scroll, timelinePane);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.34);
        split.setStyle("-fx-background-color: " + BG_BASE + "; -fx-box-border: transparent;");

        BorderPane root = new BorderPane();
        root.setTop(new VBox(header, banner));
        // The detail panel is as wide as the reader wants it: a script, or a form in editing mode,
        // needs more room than a commit's few lines. It keeps its width when the window is resized.
        detailBox.setMinWidth(220);
        SplitPane main = new SplitPane(split, detailBox);
        main.setDividerPositions(1 - 280.0 / 1180);
        SplitPane.setResizableWithParent(detailBox, false);
        main.setStyle("-fx-background-color: " + BG_BASE + "; -fx-box-border: transparent;");
        root.setCenter(main);
        root.setStyle("-fx-background-color: " + BG_BASE + ";");

        stage.setScene(new Scene(root, 1180, 760));
        stage.setOnCloseRequest(e -> { if (!mayLeaveEditing()) e.consume(); });
        updateModeControls();
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void setHost(Host host) { this.host = host; }

    /**
     * Opens the graph for the given .qtrace. Without one, shows the open image's capture in
     * progress when the host has one, else prompts a chooser.
     */
    public void show(File preselected) {
        if (preselected != null && preselected.isFile()) load(preselected);
        else if (host != null && host.liveSession(null) != null) load(null);
        else chooseFile();
        stage.show();
        stage.toFront();
        stage.setIconified(false);
    }

    public boolean isShowing()   { return stage.isShowing(); }
    public boolean isIconified() { return stage.isIconified(); }
    public void    minimize()    { stage.setIconified(true); }
    public void    front()       { stage.show(); stage.toFront(); stage.setIconified(false); }

    /**
     * Selects and highlights the commit at {@code index} (0 = first/oldest), same effect
     * as clicking its node on the canvas — populates the detail panel on the right. No-op
     * if the index is out of range or nothing is loaded yet. Used by the screenshot harness
     * so version-graph.png shows a populated detail panel instead of the empty placeholder.
     */
    public void selectNode(int index) {
        if (index < 0 || index >= nodes.size()) return;
        selectCommit(nodes.get(index));
    }

    /** Commit selected from the graph or its timeline milestone: detail panel + both views in sync. */
    private void selectCommit(Node n) {
        Integer i = milestoneIndex.get(n.index);
        if (i == null) { showDetail(n); redraw(n); return; }
        timelineList.getSelectionModel().select(i);   // listener fills the detail panel
        timelineList.scrollTo(Math.max(0, i - 3));
    }

    // ── Loading ──────────────────────────────────────────────────────────────--

    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle(QTraceI18n.t("graph.open"));
        try {
            File dir = QTraceConfig.get().readExportDir().orElse(QTraceConfig.get().getExportDir()).toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
        } catch (Exception ignored) {}
        // A saved workflow reopens where it is edited: only offered with an editor.
        if (editor() != null) fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("qTrace", "*.qtrace", "*.qtflow"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(".qtrace", "*.qtrace"));
        File f = fc.showOpenDialog(stage);
        if (f == null || !mayLeaveEditing()) return;
        leaveEditing();
        load(f);
    }

    /** {@code file} null: no .qtrace yet — only the host's capture in progress. */
    private void load(File file) {
        loadedFile = file;
        nodes.clear();
        JsonObject loaded = null;
        try {
            JsonObject root = editing != null ? editing.root() : file != null
                ? JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject() : new JsonObject();
            // A workflow file is opened to be edited, when a module can.
            if (editing == null && isWorkflow(root) && editor() != null) {
                editing = editor().open(root, file);
                root = editing.root();
            }
            loaded = root;
            if (!root.has("sessions") || !root.get("sessions").isJsonArray()) root.add("sessions", new JsonArray());
            JsonArray sessions = root.getAsJsonArray("sessions");
            // The capture in progress belongs to the record, not to a workflow.
            JsonObject live = host != null && !isWorkflow(root) ? host.liveSession(file) : null;
            if (live != null) sessions.add(live);

            String imgName = root.has("image") && root.getAsJsonObject("image").has("name")
                ? root.getAsJsonObject("image").get("name").getAsString()
                : file != null ? file.getName() : live != null ? str(live, "image_name", "") : "";
            headerLabel.setText(isWorkflow(root) ? "✎  " + str(root, "title", imgName) : "⑃  " + imgName);

            for (int i = 0; i < sessions.size(); i++) {
                nodes.add(parseNode(sessions.get(i).getAsJsonObject(), i));
            }
        } catch (Exception e) {
            headerLabel.setText(QTraceI18n.t("graph.load.error") + " — " + e.getMessage());
        }
        boolean stampAfter = false;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).covered = !nodes.get(i).stamped && stampAfter;
            stampAfter |= nodes.get(i).stamped;
        }
        layout();
        redraw(null);
        showEmptyDetail();
        buildTimeline(loaded);
        updateModeControls();
    }

    private static boolean isWorkflow(JsonObject root) {
        return "workflow".equals(str(root, "kind", null));
    }

    private final javafx.animation.PauseTransition refreshDelay =
        new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));

    /**
     * The open image's capture or its .qtrace changed (a step captured, a session committed):
     * shows it, shortly after — a burst of steps is one refresh. {@code current} is the open
     * image's .qtrace, adopted when the window was opened before that file existed. FX thread.
     */
    public void refresh(File current) {
        if (!stage.isShowing()) return;
        if (editing != null) {
            // A workflow does not follow the capture — except its recording packet, while recording.
            if (recording != null) {
                refreshDelay.setOnFinished(e -> syncRecording());
                refreshDelay.playFromStart();
            }
            return;
        }
        if (loadedFile == null && current != null) loadedFile = current;
        refreshDelay.setOnFinished(e -> {
            int size = timelineList.getItems().size();
            int top = firstVisibleRow();
            boolean atLatest = size == 0 || top <= 0;   // reading the latest, at the top: keep following
            reload(timelineList.getSelectionModel().getSelectedIndex(), top, atLatest);
        });
        refreshDelay.playFromStart();
    }

    /**
     * Another image was opened in QuPath: the window switches to its history ({@code current}:
     * its .qtrace, null when it has none yet — then only its capture in progress, if any). FX thread.
     */
    public void showImage(File current) {
        if (!stage.isShowing() || editing != null) return;
        refreshDelay.stop();
        load(current);
    }

    /** Reads the file and the capture again; the session shown, the selection and the scroll stay. */
    private void reload(int selected, int top, boolean followLatest) {
        Integer session = shownSession;
        load(loadedFile);
        if (session != null && session < nodes.size()) {
            shownSession = session;
            showTimeline();
        }
        int size = timelineList.getItems().size();
        if (selected >= 0 && selected < size) timelineList.getSelectionModel().select(selected);
        else if (session != null && session < nodes.size()) redraw(nodes.get(session));
        if (followLatest) timelineList.scrollTo(0);
        else if (top >= 0 && top < size) timelineList.scrollTo(top);
    }

    private int firstVisibleRow() {
        return timelineList.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow
            && flow.getFirstVisibleCell() != null ? flow.getFirstVisibleCell().getIndex() : -1;
    }

    /**
     * −/+ on a step row: the choice is saved by the host, then the file is read again. The
     * list keeps its place and its selection — only the rows on screen are rebuilt.
     */
    private void toggleReplaySkip(VersionTimeline.Entry e) {
        if (host == null) return;
        int selected = timelineList.getSelectionModel().getSelectedIndex();
        int top = firstVisibleRow();
        try {
            host.setReplaySkip(loadedFile, e.script(), !e.replaySkip());
        } catch (Exception ex) {
            headerLabel.setText(QTraceI18n.t("graph.step.skip.error") + " — " + ex.getMessage());
            return;
        }
        reload(selected, top, false);
    }

    private Node parseNode(JsonObject s, int index) {
        Node n = new Node();
        n.index         = index;
        n.id            = str(s, "session_id", "");
        n.parentId      = str(s, "parent_session_id", null);
        n.branch        = str(s, "branch", "main");
        n.contributor   = str(s, "user", "unknown");
        n.exportedAt    = str(s, "exported_at", "");
        n.stepsCaptured = s.has("steps_captured") ? s.get("steps_captured").getAsInt() : 0;
        n.stamped       = StampIntegrity.isStamped(s);
        n.live          = s.has("live") && s.get("live").getAsBoolean();
        n.packet        = "workflow".equals(str(s, "validation_state", null));
        if (n.packet) {
            n.notes       = str(s, "title", null);
            n.packetNotes = str(s, "notes", "");
            n.origin      = s.has("origin") && s.get("origin").isJsonObject() ? s.getAsJsonObject("origin") : null;
            n.steps       = s.has("steps") && s.get("steps").isJsonArray() ? s.getAsJsonArray("steps").size() : 0;
            for (String side : new String[] {"class_list", "display_settings"})
                if (s.has(side) && s.get(side).isJsonArray()) n.sideRecords += s.getAsJsonArray(side).size();
        }

        if (s.has("steps") && s.get("steps").isJsonArray()) {
            for (JsonElement el : s.getAsJsonArray("steps")) {
                JsonObject st = el.getAsJsonObject();
                if (st.has("pre_tracking") && st.get("pre_tracking").getAsBoolean()) n.preTracking++;
            }
        }
        if (s.has("contributions") && s.get("contributions").isJsonObject())
            n.contributions = s.getAsJsonObject("contributions");

        if (s.has("validation") && s.get("validation").isJsonObject()) {
            JsonObject v = s.getAsJsonObject("validation");
            n.validator  = str(v, "validator", null);
            n.confidence = str(v, "confidence", null);
            n.fidelity   = str(v, "classifier_fidelity", null);
            n.notes      = str(v, "notes", null);
            n.scope      = str(v, "scope", null);
            n.signed     = v.has("validatorKeyPub") && !v.get("validatorKeyPub").getAsString().isBlank();
            String ih    = str(v, "imageHash", null);
            if (ih != null && ih.length() >= 12) n.imageHashShort = ih.substring(0, 12) + "…";
        }
        return n;
    }

    // ── Layout & drawing ─────────────────────────────────────────────────────--

    private void layout() {
        for (Node n : nodes) {
            // A record: latest session first, on the left. A workflow: in the order it plays.
            n.cx = ORIGIN_X + (editing != null ? n.index : nodes.size() - 1 - n.index) * X_GAP;
            n.cy = LANE_Y;   // v1: single "main" lane
        }
        double width = Math.max(900, ORIGIN_X * 2 + Math.max(0, nodes.size() - 1) * X_GAP);
        canvas.setWidth(width);
    }

    private void redraw(Node selected) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web(BG_BASE));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        if (nodes.isEmpty()) {
            g.setFill(Color.web(TEXT_MUTED));
            g.setFont(Font.font("System", 13));
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(QTraceI18n.t("graph.empty"), 30, LANE_Y);
            return;
        }

        // Edges: parent → child (linear in v1).
        g.setStroke(Color.web(BORDER));
        g.setLineWidth(3);
        for (int i = 1; i < nodes.size(); i++) {
            Node a = nodes.get(i - 1), b = nodes.get(i);
            if (a.cx < b.cx) g.strokeLine(a.cx + NODE_R, a.cy, b.cx - NODE_R, b.cy);
            else g.strokeLine(b.cx + NODE_R, b.cy, a.cx - NODE_R, a.cy);   // a record: b, the later one, is on the left
        }

        for (Node n : nodes) drawNode(g, n, n == selected || (editing != null && pickedPackets.contains(n.index)));
    }

    private void drawNode(GraphicsContext g, Node n, boolean selected) {
        Color nodeColor = nodeColor(n);

        if (selected) {
            g.setStroke(Color.web(BLUE));
            g.setLineWidth(3);
            g.strokeOval(n.cx - NODE_R - 4, n.cy - NODE_R - 4, (NODE_R + 4) * 2, (NODE_R + 4) * 2);
        }
        if (n.stamped || n.packet) {
            g.setFill(n.packet ? Color.web(MAUVE) : nodeColor);
            g.fillOval(n.cx - NODE_R, n.cy - NODE_R, NODE_R * 2, NODE_R * 2);
        } else {
            // Unstamped (autosaved) session: hollow, dashed — recorded, not validated.
            g.setFill(Color.web(BG_BASE));
            g.fillOval(n.cx - NODE_R, n.cy - NODE_R, NODE_R * 2, NODE_R * 2);
            g.setStroke(Color.web(TEXT_MUTED));
            g.setLineWidth(2);
            g.setLineDashes(4, 4);
            g.strokeOval(n.cx - NODE_R, n.cy - NODE_R, NODE_R * 2, NODE_R * 2);
            g.setLineDashes();
        }

        // Commit index inside the node.
        g.setFill(Color.web(n.stamped || n.packet ? BG_BASE : TEXT_MUTED));
        g.setFont(Font.font("System", FontWeight.BOLD, 13));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("#" + (n.index + 1), n.cx, n.cy + 4);

        // Contributor badge (initials) above the node — one author per commit.
        double bx = n.cx, by = n.cy - NODE_R - 18;
        double br = 13;
        g.setFill(contributorColor(n.contributor));
        g.fillOval(bx - br, by - br, br * 2, br * 2);
        g.setFill(Color.web(BG_BASE));
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText(initials(n.contributor), bx, by + 3);

        // Contributor name above the badge.
        g.setFill(Color.web(TEXT_SUB));
        g.setFont(Font.font("System", 10));
        g.fillText(ellipsis(n.contributor, 18), n.cx, by - br - 4);

        // Title (notes → scope → Session #n) below the node.
        g.setFill(Color.web(TEXT_MAIN));
        g.setFont(Font.font("System", FontWeight.BOLD, 11));
        g.fillText(ellipsis(title(n), 22), n.cx, n.cy + NODE_R + 18);

        // A packet says what it holds: it has no stamp, and no date of its own.
        if (n.packet) {
            g.setFill(Color.web(MAUVE));
            g.setFont(Font.font("System", FontWeight.BOLD, 10));
            g.fillText(QTraceI18n.f("graph.edit.packet.count", n.steps), n.cx, n.cy + NODE_R + 34);
            return;
        }
        // Validator + signature mark — or the unstamped mark.
        if (!n.stamped) {
            g.setFill(Color.web(TEXT_MUTED));
            g.setFont(Font.font("System", FontWeight.BOLD, 10));
            g.fillText(QTraceI18n.t(n.live ? "graph.live" : "graph.unstamped"), n.cx, n.cy + NODE_R + 34);
        } else if (n.validator != null) {
            g.setFill(n.signed ? Color.web(GREEN) : Color.web(TEXT_MUTED));
            g.setFont(Font.font("System", 10));
            g.fillText((n.signed ? "✓ " : "") + ellipsis(n.validator, 20), n.cx, n.cy + NODE_R + 34);
        }
        // Date.
        g.setFill(Color.web(TEXT_MUTED));
        g.setFont(Font.font("System", 9));
        g.fillText(dateShort(n.exportedAt), n.cx, n.cy + NODE_R + 48);
    }

    // ── Step timeline ──────────────────────────────────────────────────────────

    private void buildTimeline(JsonObject root) {
        List<VersionTimeline.Entry> entries = new ArrayList<>();
        if (root != null)
            for (VersionTimeline.Entry e : VersionTimeline.build(root, ZoneId.systemDefault()))
                if (e.sessionIndex() < nodes.size()) entries.add(e);
        allEntries   = entries;
        timelineRoot = root;
        shownSession = null;
        showTimeline();
    }

    /** Lists {@link #allEntries} without the inherited steps, limited to {@link #shownSession} if any. */
    private void showTimeline() {
        JsonObject root = timelineRoot;
        milestoneIndex.clear();
        List<VersionTimeline.Entry> entries = editing != null
            ? VersionTimeline.workflowOrder(allEntries, shownSession) : VersionTimeline.shown(allEntries, shownSession);
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).kind() != VersionTimeline.Kind.STEP) milestoneIndex.put(entries.get(i).sessionIndex(), i);
        timelineList.getItems().setAll(entries);
        timelineFooter.setTooltip(null);
        if (editing != null) {
            showEditingFooter(entries);
            return;
        }
        if (entries.isEmpty()) {
            timelineFooter.setText("");
            return;
        }
        String footer = shownSession != null
            ? QTraceI18n.f("graph.timeline.footer.session", VersionTimeline.stepCount(entries), shownSession + 1)
            : QTraceI18n.f("graph.timeline.footer", VersionTimeline.stepCount(entries), nodes.size());
        // What a replay runs: of the session shown, or by default of everything since the last
        // stamp (the capture in progress is the last session here) — what the next stamp answers for.
        ReplaySkip.Summary replay = ReplaySkip.summary(shownSession != null
            ? ReplaySkip.steps(root.getAsJsonArray("sessions").get(shownSession).getAsJsonObject())
            : ReplaySkip.sinceLastStamp(root, null));
        if (replay.total() > 0)
            footer += "  ·  " + QTraceI18n.f(shownSession != null ? "graph.timeline.replay" : "graph.timeline.replay.since",
                replay.replayed(), replay.total());
        String sha = VersionTimeline.shortHash(root);
        timelineFooter.setText(sha.isEmpty() ? footer : footer + "  ·  sha " + sha);
        timelineList.scrollTo(0); // latest work first in view
    }

    private HBox stepRow(VersionTimeline.Entry e, Node n) {
        Circle dot = new Circle(4, Color.web(TEXT_MUTED));

        Label cmd = new Label(e.command().isBlank() ? "—" : e.command());
        fill(cmd, Color.web(TEXT_MAIN));
        cmd.setFont(Font.font("System", 12));

        HBox sub = new HBox(8);
        sub.setAlignment(Pos.CENTER_LEFT);
        if (!e.summary().isEmpty()) {
            Label s = new Label(e.summary());
            fill(s, Color.web(TEXT_MUTED));
            s.setFont(Font.font(MONO, 10));
            sub.getChildren().add(s);
        }
        String flag = stepFlag(e);
        if (flag != null) {
            Label f = new Label(flag);
            fill(f, Color.web(PEACH));
            f.setFont(Font.font("System", FontWeight.BOLD, 9));
            sub.getChildren().add(f);
        }
        VBox text = sub.getChildren().isEmpty() ? new VBox(cmd) : new VBox(1, cmd, sub);

        HBox row = timelineRow(dot, e.time(), text);
        if (flag != null) { text.setOpacity(0.55); dot.setOpacity(0.55); }
        if (editing != null) {
            HBox.setHgrow(text, Priority.ALWAYS);
            row.getChildren().add(0, dragHandle("step:" + n.index + ":" + VersionTimeline.stepIndex(allEntries, e)));
            row.getChildren().add(stepEditButtons(e, n));
        } else if (host != null && e.replayable()) {
            HBox.setHgrow(text, Priority.ALWAYS);
            row.getChildren().add(skipButton(e, n));
        }
        return row;
    }

    /** − takes the instruction out of the replay, + puts it back; frozen once the session is stamped. */
    private Region skipButton(VersionTimeline.Entry e, Node n) {
        Button b = new Button(e.replaySkip() ? "+" : "−");
        b.setFocusTraversable(false);
        b.setMinSize(24, 22);
        b.setPrefSize(24, 22);
        b.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-text-fill: " + (e.replaySkip() ? GREEN : TEXT_SUB) + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4;"
            + "-fx-font-weight: bold; -fx-padding: 0; -fx-cursor: hand;");
        StackPane box = new StackPane(b);   // a disabled control shows no tooltip: it goes on the wrapper
        if (n.stamped || n.covered) {
            b.setDisable(true);
            Tooltip.install(box, new Tooltip(QTraceI18n.t(n.stamped ? "graph.step.skip.locked" : "graph.step.skip.covered")));
        } else {
            b.setTooltip(new Tooltip(QTraceI18n.t(e.replaySkip() ? "graph.step.unskip.tip" : "graph.step.skip.tip")));
            b.setOnAction(ev -> toggleReplaySkip(e));
        }
        return box;
    }

    private HBox milestoneRow(VersionTimeline.Entry e, Node n) {
        if (n.packet) return packetRow(e, n);
        boolean stamped = e.kind() == VersionTimeline.Kind.STAMP;
        Circle dot = new Circle(7);
        if (stamped) {
            dot.setFill(nodeColor(n));
        } else {
            dot.setFill(Color.web(BG_BASE));
            dot.setStroke(Color.web(TEXT_MUTED));
            dot.setStrokeWidth(2);
            dot.getStrokeDashArray().setAll(3.0, 3.0);
        }

        Label head = new Label(QTraceI18n.t(stamped ? "graph.detail.stamped" : n.live ? "graph.live" : "graph.unstamped")
            + "  ·  #" + (n.index + 1) + " — " + title(n));
        fill(head, stamped ? nodeColor(n) : Color.web(TEXT_MUTED));
        head.setFont(Font.font("System", FontWeight.BOLD, 12));

        List<String> parts = new ArrayList<>();
        if (stamped && n.validator != null) parts.add((n.signed ? "✓ " : "") + n.validator);
        else parts.add(n.contributor);
        if (stamped && n.confidence != null) parts.add(n.confidence);
        parts.add(dateShort(e.timestampIso()));
        Label sub = new Label(String.join("  ·  ", parts));
        fill(sub, Color.web(stamped && n.signed ? GREEN : TEXT_SUB));
        sub.setFont(Font.font(MONO, 10));

        HBox row = timelineRow(dot, e.time(), new VBox(1, head, sub));
        row.setPadding(new Insets(8, 14, 8, 11));
        return row;
    }

    /** Rail cell (continuous vertical line + dot) · time · text. */
    private HBox timelineRow(Circle dot, String time, VBox text) {
        Region line = new Region();
        line.setMinWidth(2);
        line.setMaxWidth(2);
        line.setMaxHeight(Double.MAX_VALUE);
        line.setStyle("-fx-background-color: " + BORDER + ";");
        StackPane rail = new StackPane(line, dot);
        rail.setMinWidth(24);
        rail.setPrefWidth(24);

        Label t = new Label(time);
        fill(t, Color.web(TEXT_MUTED));
        t.setFont(Font.font(MONO, 11));
        t.setMinWidth(66);

        HBox row = new HBox(10, rail, t, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 14, 4, 11));
        row.setFillHeight(true);
        row.setStyle(ROW_IDLE);
        row.setCursor(javafx.scene.Cursor.HAND);
        return row;
    }

    /**
     * Inside the timeline list, modena's label rule wins over setTextFill() (rows came out
     * grey) — an inline -fx-text-fill outranks every stylesheet.
     */
    private static void fill(Label l, Color c) {
        l.setStyle("-fx-text-fill: " + toHex(c) + ";");
    }

    /**
     * One recycled timeline row. The row is rebuilt per item (only ~20 are ever on screen); the
     * cell itself stays transparent and borderless so the rail reads as one continuous line, and
     * its inline style replaces modena's blue selection with the peach bar.
     */
    private final class TimelineCell extends ListCell<VersionTimeline.Entry> {
        private HBox row;

        TimelineCell() {
            setStyle("-fx-padding: 0; -fx-background-color: transparent;");
            selectedProperty().addListener((o, was, sel) -> applySelection());
            // Editing mode: a row is where a dragged packet or instruction is dropped.
            setOnDragOver(ev -> {
                Drag d = editing != null ? dragOf(ev.getDragboard()) : null;
                if (d == null || row == null || getItem() == null || !landsElsewhere(d, getItem(), lowerHalf(ev))) return;
                ev.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                row.setStyle(dropsAfter(d, getItem(), lowerHalf(ev)) ? ROW_DROP_AFTER : ROW_DROP_BEFORE);
                ev.consume();
            });
            setOnDragExited(ev -> applySelection());
            setOnDragDropped(ev -> {
                Drag d = editing != null ? dragOf(ev.getDragboard()) : null;
                VersionTimeline.Entry target = getItem();
                boolean lower = lowerHalf(ev);
                boolean ok = d != null && target != null && landsElsewhere(d, target, lower);
                ev.setDropCompleted(ok);
                ev.consume();
                // Once the drop is over: the change rebuilds the very rows the gesture runs on.
                if (ok) javafx.application.Platform.runLater(() -> drop(d, target, lower));
            });
        }

        private boolean lowerHalf(javafx.scene.input.DragEvent ev) {
            return ev.getY() > getHeight() / 2;
        }

        @Override
        protected void updateItem(VersionTimeline.Entry e, boolean empty) {
            super.updateItem(e, empty);
            setText(null);
            if (empty || e == null || e.sessionIndex() >= nodes.size()) { row = null; setGraphic(null); return; }
            Node n = nodes.get(e.sessionIndex());
            row = e.kind() == VersionTimeline.Kind.STEP ? stepRow(e, n) : milestoneRow(e, n);
            applySelection();
            setGraphic(row);
        }

        private void applySelection() {
            if (row != null) row.setStyle(isSelected() ? ROW_SELECTED : ROW_IDLE);
        }
    }

    private static String stepFlag(VersionTimeline.Entry e) {
        if (e.deleted())        return QTraceI18n.t("graph.step.deleted");
        if (e.replayExcluded()) return QTraceI18n.t("graph.step.excluded");
        if (e.replaySkip())     return QTraceI18n.t("graph.step.skipped");
        if (e.preTracking())    return QTraceI18n.t("graph.step.inherited");
        return null;
    }

    private void showStepDetail(VersionTimeline.Entry e, Node n) {
        detailBox.getChildren().clear();
        detailBox.getChildren().add(sectionTitle(e.command().isBlank() ? "—" : e.command()));
        detailBox.getChildren().add(kv(QTraceI18n.t("graph.step.time"),
            dateShort(e.timestampIso()) + (e.time().isEmpty() ? "" : "  (" + e.time() + ")")));
        detailBox.getChildren().add(kv(QTraceI18n.t("graph.step.session"), "#" + (n.index + 1) + " — " + title(n)));
        if (e.source() == VersionTimeline.Source.WORKFLOW) {
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.step.scriptable"),
                QTraceI18n.t(e.scriptable() ? "graph.step.yes" : "graph.step.no")));
        } else {
            detailBox.getChildren().add(muted(QTraceI18n.t("graph.rec.captured")));
            for (var d : e.details().entrySet())
                detailBox.getChildren().add(kv(QTraceI18n.t(d.getKey()), d.getValue()));
        }
        String flag = stepFlag(e);
        if (e.replaySkip() && !e.deleted() && !e.replayExcluded())
            detailBox.getChildren().add(muted(QTraceI18n.f("graph.step.skipped.by",
                e.skip().by().isBlank() ? "?" : e.skip().by(), dateShort(e.skip().at()))));
        else if (flag != null) detailBox.getChildren().add(muted(flag));
        if (e.script() != null && !e.script().isBlank()) {
            detailBox.getChildren().add(sectionTitle(QTraceI18n.t("graph.step.script")));
            Label script = new Label(ellipsis(e.script(), 1200));
            script.setWrapText(true);
            script.setTextFill(Color.web(TEXT_SUB));
            script.setFont(Font.font(MONO, 10));
            detailBox.getChildren().add(script);
        }
        if (editing != null) addStepEditActions(e, n);
    }

    // ── Editing mode (VersionEditor) ───────────────────────────────────────────

    /** The module's editor, when the licence includes one and the window belongs to QuPath. */
    private VersionEditor editor() {
        List<VersionEditor> editors = host != null ? VersionEditors.entitled() : List.of();
        return editors.isEmpty() ? null : editors.get(0);
    }

    private void updateModeControls() {
        VersionEditor ed = editor();
        boolean on = editing != null;
        show(modeBtn, on || ed != null);
        modeBtn.setText(on ? QTraceI18n.t("graph.edit.exit") : ed != null ? ed.label() : "");
        show(recordBtn, on);
        boolean rec = recording != null;
        recordBtn.setText(QTraceI18n.t(rec ? "graph.edit.record.stop" : "graph.edit.record"));
        recordBtn.setTooltip(new Tooltip(QTraceI18n.t(rec ? "graph.edit.record.stop.tip" : "graph.edit.record.tip")));
        recordBtn.setStyle("-fx-background-color: " + (rec ? RED : BG_SURFACE) + "; -fx-text-fill: " + (rec ? BG_BASE : RED) + ";"
            + "-fx-border-color: " + (rec ? RED : BORDER) + "; -fx-border-radius: 4; -fx-background-radius: 4;"
            + "-fx-cursor: hand; -fx-padding: 4 10 4 10; -fx-font-weight: bold;");
        banner.setText(QTraceI18n.t(rec ? "graph.edit.record.banner" : "graph.edit.banner"));
        banner.setStyle("-fx-background-color: " + (rec ? RED : MAUVE) + "; -fx-text-fill: " + BG_BASE + ";");
        show(addPacketBtn, on);
        show(mergeBtn, on);
        mergeBtn.setText(pickedPackets.size() >= 2
            ? QTraceI18n.f("graph.edit.merge.n", pickedPackets.size()) : QTraceI18n.t("graph.edit.merge"));
        mergeBtn.setDisable(pickedPackets.size() < 2);
        show(saveBtn, on);
        show(publishBtn, on);
        show(consoleBox, on);
        updateConsoleControls();
        javafx.scene.control.SelectionMode mode = on ? javafx.scene.control.SelectionMode.MULTIPLE : javafx.scene.control.SelectionMode.SINGLE;
        if (timelineList.getSelectionModel().getSelectionMode() != mode) timelineList.getSelectionModel().setSelectionMode(mode);
        show(banner, on);
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * Enters the mode on what the window shows, or leaves it. Entering: the record, or, when the
     * image has none yet, its capture in progress — or nothing at all, and the workflow starts
     * empty. Leaving: the record is read again as it is on disk; after a workflow opened from
     * its own file, the window goes back to the image open in QuPath.
     */
    private void toggleEditing() {
        if (editing != null) {
            if (!mayLeaveEditing()) return;
            boolean ownFile = loadedFile != null && loadedFile.getName().endsWith(".qtflow");
            File record = !ownFile ? loadedFile : host != null ? host.currentRecord() : null;
            leaveEditing();
            load(record);
            return;
        }
        VersionEditor ed = editor();
        if (ed == null) return;
        try {
            JsonObject root;
            if (loadedFile != null) {
                root = JsonParser.parseString(Files.readString(loadedFile.toPath())).getAsJsonObject();
            } else {
                root = new JsonObject();
                JsonArray sessions = new JsonArray();
                JsonObject live = host != null ? host.liveSession(null) : null;
                if (live != null) sessions.add(live);
                root.add("sessions", sessions);
            }
            editing = ed.open(root, loadedFile);
        } catch (Exception ex) {
            headerLabel.setText(QTraceI18n.t("graph.load.error") + " — " + ex.getMessage());
            return;
        }
        editingDirty = false;
        editingStatus = "";
        load(loadedFile);
        log(QTraceI18n.f("graph.edit.console.opened", loadedFile != null ? loadedFile.getName() : "—", nodes.size()));
    }

    /**
     * Ctrl+click adds a packet to the ones picked or takes it out; Shift+click picks every
     * packet from the last one clicked to this one. With several picked, the list shows the
     * whole workflow and the panel says what merging them gives.
     */
    private void pickPacket(int index, boolean range) {
        if (range && lastPicked >= 0) {
            for (int i = Math.min(lastPicked, index); i <= Math.max(lastPicked, index); i++) pickedPackets.add(i);
        } else if (!pickedPackets.remove(index)) {
            pickedPackets.add(index);
        }
        lastPicked = index;
        shownSession = null;
        showTimeline();
        timelineList.getSelectionModel().clearSelection();
        redraw(null);
        updateModeControls();

        detailBox.getChildren().clear();
        detailBox.getChildren().add(sectionTitle(QTraceI18n.f("graph.edit.picked", pickedPackets.size())));
        for (int i : pickedPackets)
            if (i < nodes.size()) detailBox.getChildren().add(bullet("#" + (i + 1) + " — " + title(nodes.get(i))));
        detailBox.getChildren().add(muted(QTraceI18n.t("graph.edit.merge.tip")));
    }

    /** Merges the picked packets into the first of them, their instructions in the order the packets play. */
    private void mergePicked() {
        if (editing == null || pickedPackets.size() < 2) return;
        List<Integer> picked = new ArrayList<>(pickedPackets);
        int first = picked.get(0);
        edit(() -> editing.mergePackets(picked), -1);
        shownSession = null;
        showTimeline();
        Integer row = milestoneIndex.get(first);
        if (row != null) timelineList.getSelectionModel().select(row);
    }

    // ── Editing mode: console ──────────────────────────────────────────────────

    /** One line in the console, with its time; the console unfolds when something is said. */
    private void log(String line) {
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        consoleArea.appendText((consoleArea.getText().isEmpty() ? "" : "\n") + time + "  " + line);
        if (!consoleArea.isVisible()) show(consoleArea, true);
        updateConsoleControls();
    }

    private void updateConsoleControls() {
        consoleToggle.setText((consoleArea.isVisible() ? "▾  " : "▸  ") + QTraceI18n.t("graph.edit.console"));
        show(consolePlayBtn, lastPublishedId != null);
    }

    /** Opens the replay player on the workflow just published. */
    private void playPublished() {
        if (lastPublishedId == null) return;
        if (host != null && host.playInPlayer(lastPublishedId)) log(QTraceI18n.f("graph.edit.console.playing", lastPublishedId));
        else log(QTraceI18n.f("graph.edit.console.noplayer", lastPublishedId));
    }

    // ── Editing mode: recording ────────────────────────────────────────────────

    /**
     * Record: a new packet at the end of the workflow receives what the user does in QuPath
     * from now on. Stop: the packet is kept as it is — or removed when nothing was done.
     */
    private void toggleRecording() {
        if (editing == null) return;
        if (recording != null) {
            syncRecording();
            int packet = recordingPacket();
            recording = null;
            recordingPacketId = null;
            if (packet >= 0 && nodes.get(packet).steps == 0) edit(() -> editing.removePacket(packet), -1);
            else updateModeControls();
            return;
        }
        recording = new WorkflowRecording(host != null ? host.liveSession(null) : null);
        edit(() -> editing.addPacket(nodes.size(), QTraceI18n.t("graph.edit.record.packet")), -1);
        JsonArray packets = editing.root().getAsJsonArray("sessions");
        recordingPacketId = packets.isEmpty() ? null : str(packets.get(packets.size() - 1).getAsJsonObject(), "session_id", null);
        updateModeControls();
        Integer row = milestoneIndex.get(nodes.size() - 1);   // the new packet, last in the list
        if (row != null) {
            timelineList.getSelectionModel().select(row);
            timelineList.scrollTo(row);
        }
    }

    /** Where the recording packet is now, or -1 when it was removed. */
    private int recordingPacket() {
        if (editing == null || recordingPacketId == null) return -1;
        JsonArray packets = editing.root().getAsJsonArray("sessions");
        for (int i = 0; i < packets.size(); i++)
            if (recordingPacketId.equals(str(packets.get(i).getAsJsonObject(), "session_id", null))) return i;
        return -1;
    }

    /**
     * Brings the recording packet up to date with the capture: it holds exactly what was done
     * since Record, as it is now (a reshaped annotation keeps one instruction, with its last
     * shape). Until Stop the packet follows the capture: changes made to it by hand are replaced.
     */
    private void syncRecording() {
        if (editing == null || recording == null) return;
        int packet = recordingPacket();
        if (packet < 0) {   // the author removed the packet: nothing left to record into
            recording = null;
            recordingPacketId = null;
            updateModeControls();
            return;
        }
        List<JsonObject> done = recording.recorded(host != null ? host.liveSession(null) : null);
        JsonArray held = editing.root().getAsJsonArray("sessions").get(packet).getAsJsonObject().getAsJsonArray("steps");
        boolean same = held.size() == done.size();
        for (int i = 0; same && i < done.size(); i++) {
            JsonObject h = held.get(i).getAsJsonObject();
            same = str(h, "command", "").equals(str(done.get(i), "command", ""))
                && str(h, "script_fragment", "").equals(str(done.get(i), "script_fragment", ""));
        }
        if (same) return;

        int top = firstVisibleRow();
        int selected = timelineList.getSelectionModel().getSelectedIndex();
        for (int i = held.size() - 1; i >= 0; i--) editing.removeStep(packet, i);
        for (int i = 0; i < done.size(); i++)
            editing.addStep(packet, i, str(done.get(i), "command", ""), str(done.get(i), "script_fragment", ""));
        editingDirty = true;
        editingStatus = "";
        reload(selected, top, false);
    }

    private void leaveEditing() {
        lastPublishedId = null;
        consoleArea.clear();
        recording = null;
        recordingPacketId = null;
        pickedPackets.clear();
        lastPicked = -1;
        editing = null;
        editingDirty = false;
        editingStatus = "";
        shownSession = null;
    }

    /** False when the author keeps editing rather than lose unsaved changes. */
    private boolean mayLeaveEditing() {
        if (editing == null || !editingDirty) return true;
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION, QTraceI18n.t("graph.edit.discard"),
            javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);   // Esc = Cancel
        alert.setHeaderText(null);
        alert.setTitle(QTraceI18n.t("graph.window.title"));
        alert.initOwner(stage);
        return alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == javafx.scene.control.ButtonType.OK;
    }

    /**
     * Applies a change to the workflow, then shows it: the list keeps its place, and
     * {@code select} (a row, -1 for none) is selected.
     */
    private void edit(Runnable change, int select) {
        if (editing == null) return;
        int top = firstVisibleRow();
        change.run();
        pickedPackets.clear();   // positions changed: what was picked no longer names the same packets
        lastPicked = -1;
        editingDirty = true;
        editingStatus = "";
        reload(select, top, false);
    }

    private void saveWorkflow() {
        if (editing == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle(QTraceI18n.t("graph.edit.save"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(".qtflow", "*.qtflow"));
        if (loadedFile != null) {
            File dir = loadedFile.getParentFile();
            if (dir != null && dir.isDirectory()) fc.setInitialDirectory(dir);
            String name = loadedFile.getName();
            int dot = name.lastIndexOf('.');
            fc.setInitialFileName((dot > 0 ? name.substring(0, dot) : name) + ".qtflow");
        }
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        try {
            editing.save(f);
            editingDirty = false;
            editingStatus = QTraceI18n.f("graph.edit.saved", f.getName());
            log(QTraceI18n.f("graph.edit.console.saved", f.getAbsolutePath()));
            for (String w : editing.warnings()) log("⚠ " + w);
        } catch (Exception ex) {
            editingStatus = QTraceI18n.t("graph.edit.save.error") + " — " + ex.getMessage();
            log("✗ " + editingStatus);
        }
        showEditingFooter(timelineList.getItems());
    }

    /**
     * Publishes the workflow on qtrace.ca, once its author has confirmed: it leaves the
     * workstation. The module does the sending, off the FX thread; its ID is copied so it can be
     * handed to whoever attaches it to a training.
     */
    private void publishWorkflow() {
        if (editing == null) return;
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION, QTraceI18n.t("graph.edit.publish.confirm"),
            javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);   // Esc = Cancel
        confirm.setHeaderText(null);
        confirm.setTitle(QTraceI18n.t("graph.window.title"));
        confirm.initOwner(stage);
        if (confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) return;

        VersionEditor.Document doc = editing;
        publishBtn.setDisable(true);
        // A spinner in the button for as long as the workflow and its classifiers travel.
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        publishBtn.setGraphic(spinner);
        publishBtn.setText(QTraceI18n.t("graph.edit.publishing"));
        editingStatus = QTraceI18n.t("graph.edit.publishing");
        log(QTraceI18n.t("graph.edit.console.publishing"));
        showEditingFooter(timelineList.getItems());
        Thread t = new Thread(() -> {
            String published = null, failure = null;
            try {
                published = doc.publish();
            } catch (Exception ex) {
                failure = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            }
            String ok = published, ko = failure;
            javafx.application.Platform.runLater(() -> {
                publishBtn.setDisable(false);
                publishBtn.setGraphic(null);
                publishBtn.setText(QTraceI18n.t("graph.edit.publish"));
                if (editing != doc) return;   // the author left the workflow meanwhile
                if (ok != null) {
                    javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                    content.putString(ok.split(" ")[0]);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                    editingStatus = QTraceI18n.f("graph.edit.published", ok);
                    lastPublishedId = ok.split(" ")[0];
                    log(QTraceI18n.f("graph.edit.console.published", ok));
                    log(QTraceI18n.f("graph.edit.console.howtoplay", lastPublishedId));
                    for (String w : doc.warnings()) log("⚠ " + w);
                } else {
                    log("✗ " + QTraceI18n.t("graph.edit.publish.error") + " — " + ko);
                    editingStatus = QTraceI18n.t("graph.edit.publish.error");
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR, ko, javafx.scene.control.ButtonType.OK);
                    alert.setHeaderText(QTraceI18n.t("graph.edit.publish.error"));
                    alert.setTitle(QTraceI18n.t("graph.window.title"));
                    alert.initOwner(stage);
                    alert.show();
                }
                showEditingFooter(timelineList.getItems());
            });
        }, "qtrace-workflow-publish");
        t.setDaemon(true);
        t.start();
    }

    /** What the workflow holds, the last save, and what its author should know before saving. */
    private void showEditingFooter(List<VersionTimeline.Entry> entries) {
        String footer = QTraceI18n.f("graph.edit.footer", VersionTimeline.stepCount(entries), nodes.size());
        if (!editingStatus.isEmpty()) footer += "  ·  " + editingStatus;
        List<String> warnings = editing.warnings();
        if (!warnings.isEmpty()) {
            footer += "  ·  ⚠ " + warnings.get(0) + (warnings.size() > 1 ? "  (+" + (warnings.size() - 1) + ")" : "");
            timelineFooter.setTooltip(new Tooltip(String.join("\n", warnings)));
        }
        timelineFooter.setText(footer);
    }

    private int rowOf(VersionTimeline.Entry e) {
        List<VersionTimeline.Entry> rows = timelineList.getItems();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) == e) return i;
        return -1;
    }

    private Button rowButton(String text, String tipKey, Runnable action) {
        Button b = new Button(text);
        b.setFocusTraversable(false);
        b.setMinSize(24, 22);
        b.setPrefSize(24, 22);
        b.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-text-fill: " + TEXT_SUB + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4;"
            + "-fx-font-weight: bold; -fx-padding: 0; -fx-cursor: hand;");
        b.setTooltip(new Tooltip(QTraceI18n.t(tipKey)));
        b.setOnAction(ev -> action.run());
        return b;
    }

    /** ↑ ↓ move the instruction inside its packet, ✕ takes it out of the workflow. */
    private Region stepEditButtons(VersionTimeline.Entry e, Node n) {
        int si = VersionTimeline.stepIndex(allEntries, e);
        // On a row of a group of selected instructions, the buttons act on the whole group.
        Button up = rowButton("↑", "graph.edit.step.up", () -> {
            if (inGroup(e)) shiftSelectedSteps(-1); else edit(() -> editing.moveStep(n.index, si, -1), rowOf(e) - 1);
        });
        Button down = rowButton("↓", "graph.edit.step.down", () -> {
            if (inGroup(e)) shiftSelectedSteps(1); else edit(() -> editing.moveStep(n.index, si, 1), rowOf(e) + 1);
        });
        Button remove = rowButton("✕", "graph.edit.step.remove", () -> {
            if (inGroup(e)) removeSelectedSteps(); else edit(() -> editing.removeStep(n.index, si), -1);
        });
        up.setDisable(si <= 0 && !inGroup(e));
        down.setDisable(si >= n.steps - 1 && !inGroup(e));
        HBox box = new HBox(4, up, down, remove);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /** A packet's row: its title, what it holds, and — while editing — what can be done with it. */
    private HBox packetRow(VersionTimeline.Entry e, Node n) {
        Circle dot = new Circle(7, Color.web(MAUVE));
        Label head = new Label("#" + (n.index + 1) + " — " + title(n));
        fill(head, Color.web(MAUVE));
        head.setFont(Font.font("System", FontWeight.BOLD, 12));
        Label sub = new Label(QTraceI18n.f("graph.edit.packet.count", n.steps)
            + (n.sideRecords > 0 ? "  ·  " + QTraceI18n.f("graph.edit.packet.side", n.sideRecords) : ""));
        fill(sub, Color.web(TEXT_SUB));
        sub.setFont(Font.font(MONO, 10));
        VBox text = new VBox(1, head, sub);

        HBox row = timelineRow(dot, "", text);
        row.setPadding(new Insets(8, 14, 8, 11));
        if (editing == null) return row;

        Button add = rowButton("＋", "graph.edit.step.add", () -> showStepForm(n, n.steps, "", "", true));
        Button up = rowButton("↑", "graph.edit.packet.up", () -> edit(() -> editing.movePacket(n.index, -1), -1));
        Button down = rowButton("↓", "graph.edit.packet.down", () -> edit(() -> editing.movePacket(n.index, 1), -1));
        Button merge = rowButton("⤓", "graph.edit.packet.merge", () -> edit(() -> editing.mergeWithNext(n.index), -1));
        Button remove = rowButton("✕", "graph.edit.packet.remove", () -> edit(() -> editing.removePacket(n.index), -1));
        up.setDisable(n.index == 0);
        down.setDisable(n.index >= nodes.size() - 1);
        merge.setDisable(n.index >= nodes.size() - 1);
        HBox box = new HBox(4, add, up, down, merge, remove);
        box.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(text, Priority.ALWAYS);
        row.getChildren().add(0, dragHandle("packet:" + n.index));
        row.getChildren().add(box);
        return row;
    }

    // ── Editing mode: drag and drop ────────────────────────────────────────────

    /** The selected instructions being dragged together by the grip of one of them; null for a single drag. */
    private List<VersionEditor.Step> draggedGroup;

    // ── Editing mode: several instructions at once ─────────────────────────────

    /** The instructions selected in the list, in the order they play — packet rows left out. */
    private List<VersionEditor.Step> selectedSteps() {
        List<VersionEditor.Step> out = new ArrayList<>();
        if (editing == null) return out;
        // By row, not by value: two instructions alike are two rows, selected each on its own.
        List<VersionTimeline.Entry> rows = timelineList.getItems();
        for (int i : new java.util.TreeSet<>(timelineList.getSelectionModel().getSelectedIndices())) {
            if (i < 0 || i >= rows.size() || rows.get(i).kind() != VersionTimeline.Kind.STEP) continue;
            int si = VersionTimeline.stepIndex(allEntries, rows.get(i));
            if (si >= 0) out.add(new VersionEditor.Step(rows.get(i).sessionIndex(), si));
        }
        return out;
    }

    /** True when this row is one of several selected instructions: its buttons and its grip act on all of them. */
    private boolean inGroup(VersionTimeline.Entry e) {
        List<VersionEditor.Step> picked = selectedSteps();
        return picked.size() > 1 && picked.contains(new VersionEditor.Step(e.sessionIndex(), VersionTimeline.stepIndex(allEntries, e)));
    }

    private void removeSelectedSteps() {
        List<VersionEditor.Step> picked = selectedSteps();
        if (!picked.isEmpty()) edit(() -> editing.removeSteps(picked), -1);
    }

    private void shiftSelectedSteps(int delta) {
        List<VersionEditor.Step> picked = selectedSteps();
        if (picked.isEmpty()) return;
        List<List<VersionEditor.Step>> now = new ArrayList<>();
        edit(() -> now.add(editing.shiftSteps(picked, delta)), -1);
        if (!now.isEmpty()) selectSteps(now.get(0));
    }

    /** Selects the rows of these instructions, after a change rebuilt the list. */
    private void selectSteps(List<VersionEditor.Step> steps) {
        timelineList.getSelectionModel().clearSelection();
        List<VersionTimeline.Entry> rows = timelineList.getItems();
        int[] next = new int[nodes.size() + 1];
        for (int i = 0; i < rows.size(); i++) {
            VersionTimeline.Entry e = rows.get(i);
            if (e.kind() != VersionTimeline.Kind.STEP || e.sessionIndex() >= nodes.size()) continue;
            if (steps.contains(new VersionEditor.Step(e.sessionIndex(), next[e.sessionIndex()]++))) timelineList.getSelectionModel().select(i);
        }
    }

    /** The detail panel for several selected instructions: what they are, and what can be done with them. */
    private void showStepsPickedDetail() {
        List<VersionTimeline.Entry> picked = new ArrayList<>();
        List<VersionTimeline.Entry> rows = timelineList.getItems();
        for (int i : new java.util.TreeSet<>(timelineList.getSelectionModel().getSelectedIndices()))
            if (i >= 0 && i < rows.size() && rows.get(i).kind() == VersionTimeline.Kind.STEP) picked.add(rows.get(i));
        detailBox.getChildren().clear();
        detailBox.getChildren().add(sectionTitle(QTraceI18n.f("graph.edit.steps.picked", picked.size())));
        for (int i = 0; i < picked.size() && i < 15; i++)
            detailBox.getChildren().add(bullet(ellipsis(picked.get(i).command().isBlank() ? "—" : picked.get(i).command(), 40)));
        if (picked.size() > 15) detailBox.getChildren().add(muted("… +" + (picked.size() - 15)));
        detailBox.getChildren().add(muted(QTraceI18n.t("graph.edit.steps.tip")));
        Button remove = new Button(QTraceI18n.f("graph.edit.steps.remove", picked.size()));
        styleButton(remove);
        remove.setOnAction(ev -> removeSelectedSteps());
        HBox actions = new HBox(8, remove);
        actions.setPadding(new Insets(10, 0, 0, 0));
        detailBox.getChildren().add(actions);
    }

    /** What is being dragged: a whole packet ({@code step} -1), or one instruction of it. */
    private record Drag(int packet, int step) {
        boolean wholePacket() { return step < 0; }
    }

    private static Drag dragOf(javafx.scene.input.Dragboard db) {
        return db != null && db.hasString() ? dragOfText(db.getString()) : null;
    }

    private static Drag dragOfText(String text) {
        if (text == null || !text.startsWith(DRAG_PREFIX)) return null;
        try {
            String[] parts = text.substring(DRAG_PREFIX.length()).split(":");
            if (parts[0].equals("packet") && parts.length == 2) return new Drag(Integer.parseInt(parts[1]), -1);
            if (parts[0].equals("step") && parts.length == 3)
                return new Drag(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            // Some other text on the dragboard.
        }
        return null;
    }

    /** The grip at the left of a row: a packet's moves it with all its instructions, an instruction's moves it alone. */
    private Label dragHandle(String what) {
        Label h = new Label("⋮⋮");
        fill(h, Color.web(TEXT_MUTED));
        h.setFont(Font.font("System", FontWeight.BOLD, 13));
        h.setMinWidth(16);
        h.setCursor(javafx.scene.Cursor.OPEN_HAND);
        h.setTooltip(new Tooltip(QTraceI18n.t("graph.edit.drag")));
        // A press on the grip is not a click on the row: the selection stays as it is, so a
        // selected row can be dragged with the others selected.
        h.setOnMousePressed(javafx.scene.input.MouseEvent::consume);
        h.setOnDragDetected(ev -> {
            Drag dragged = dragOfText(DRAG_PREFIX + what);
            List<VersionEditor.Step> picked = selectedSteps();
            draggedGroup = dragged != null && !dragged.wholePacket() && picked.size() > 1
                && picked.contains(new VersionEditor.Step(dragged.packet(), dragged.step())) ? picked : null;
            javafx.scene.input.Dragboard db = h.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(DRAG_PREFIX + what);
            db.setContent(content);
            if (h.getParent() != null) db.setDragView(h.getParent().snapshot(null, null));
            ev.consume();
        });
        return h;
    }

    /**
     * Where a dragged instruction lands in the target row's packet: before or after an
     * instruction row (upper or lower half), first of the packet on the packet's own row.
     */
    private int landingStep(VersionTimeline.Entry target, boolean lowerHalf) {
        if (target.kind() != VersionTimeline.Kind.STEP) return 0;
        int at = VersionTimeline.stepIndex(allEntries, target);
        return at < 0 ? -1 : at + (lowerHalf ? 1 : 0);
    }

    /** False when the drop would leave everything where it is. */
    private boolean landsElsewhere(Drag d, VersionTimeline.Entry target, boolean lowerHalf) {
        if (d.wholePacket()) return target.sessionIndex() != d.packet();
        int at = landingStep(target, lowerHalf);
        if (at < 0) return false;
        // A group lands anywhere but on one of its own rows.
        if (draggedGroup != null) {
            return target.kind() != VersionTimeline.Kind.STEP || !draggedGroup.contains(
                new VersionEditor.Step(target.sessionIndex(), VersionTimeline.stepIndex(allEntries, target)));
        }
        return target.sessionIndex() != d.packet() || (at != d.step() && at != d.step() + 1);
    }

    /** The drop line: under the row when the dragged item lands after it. */
    private boolean dropsAfter(Drag d, VersionTimeline.Entry target, boolean lowerHalf) {
        // A packet takes the place of the packet it is dropped on.
        if (d.wholePacket()) return d.packet() < target.sessionIndex();
        return target.kind() != VersionTimeline.Kind.STEP || lowerHalf;
    }

    private void drop(Drag d, VersionTimeline.Entry target, boolean lowerHalf) {
        if (editing == null) return;
        int toPacket = target.sessionIndex();
        if (d.wholePacket()) {
            if (shownSession != null) shownSession = toPacket;   // the packet shown alone keeps being shown
            edit(() -> editing.movePacket(d.packet(), toPacket - d.packet()), -1);
            Integer row = milestoneIndex.get(toPacket);
            if (row != null) timelineList.getSelectionModel().select(row);
            return;
        }
        int at = landingStep(target, lowerHalf);
        if (at < 0) return;
        if (draggedGroup != null) {
            List<VersionEditor.Step> group = draggedGroup;
            draggedGroup = null;
            List<List<VersionEditor.Step>> now = new ArrayList<>();
            edit(() -> now.add(editing.moveSteps(group, toPacket, at)), -1);
            if (!now.isEmpty()) selectSteps(now.get(0));
            return;
        }
        int landed = toPacket == d.packet() && at > d.step() ? at - 1 : at;
        edit(() -> editing.moveStepTo(d.packet(), d.step(), toPacket, at), -1);
        int k = 0;
        List<VersionTimeline.Entry> rows = timelineList.getItems();
        for (int i = 0; i < rows.size(); i++) {
            VersionTimeline.Entry e = rows.get(i);
            if (e.kind() != VersionTimeline.Kind.STEP || e.sessionIndex() != toPacket) continue;
            if (k++ == landed) { timelineList.getSelectionModel().select(i); return; }
        }
    }

    /** Under an instruction's detail: rewrite it, or cut its packet in two from here on. */
    private void addStepEditActions(VersionTimeline.Entry e, Node n) {
        int si = VersionTimeline.stepIndex(allEntries, e);
        if (si < 0) return;
        Button editBtn = new Button(QTraceI18n.t("graph.edit.edit"));
        styleButton(editBtn);
        editBtn.setOnAction(ev -> showStepForm(n, si, e.command(), e.script(), false));
        Button split = new Button(QTraceI18n.t("graph.edit.packet.split"));
        styleButton(split);
        split.setDisable(si == 0);   // a packet is not split before its first instruction
        split.setOnAction(ev -> edit(() -> editing.splitPacket(n.index, si), -1));
        HBox actions = new HBox(8, editBtn, split);
        actions.setPadding(new Insets(10, 0, 0, 0));
        detailBox.getChildren().add(actions);
    }

    /** The detail panel as a form: an instruction's title and script, to rewrite or to add. */
    private void showStepForm(Node n, int at, String title, String script, boolean isNew) {
        javafx.scene.control.TextField titleField = new javafx.scene.control.TextField(title);
        javafx.scene.control.TextArea scriptArea = new javafx.scene.control.TextArea(script == null ? "" : script);
        scriptArea.setFont(Font.font(MONO, 11));
        scriptArea.setPrefRowCount(18);
        VBox.setVgrow(scriptArea, Priority.ALWAYS);

        int selected = timelineList.getSelectionModel().getSelectedIndex();
        Button ok = new Button(QTraceI18n.t(isNew ? "graph.edit.add" : "graph.edit.apply"));
        styleButton(ok);
        ok.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
            () -> scriptArea.getText().isBlank(), scriptArea.textProperty()));
        ok.setOnAction(ev -> edit(() -> {
            if (isNew) editing.addStep(n.index, at, titleField.getText(), scriptArea.getText());
            else editing.editStep(n.index, at, titleField.getText(), scriptArea.getText());
        }, isNew ? -1 : selected));

        titleField.setPromptText(QTraceI18n.t("graph.edit.title.prompt"));
        detailBox.getChildren().setAll(
            sectionTitle(QTraceI18n.t(isNew ? "graph.edit.step.new" : "graph.edit.step.edit")),
            muted("#" + (n.index + 1) + " — " + title(n)),
            sectionTitle(QTraceI18n.t("graph.edit.title")), titleField,
            sectionTitle(QTraceI18n.t("graph.step.script")), scriptArea,
            formButtons(ok));
        // The title first: it is what the list and the player show for this instruction.
        javafx.application.Platform.runLater(() -> { titleField.requestFocus(); titleField.selectAll(); });
    }

    /** The detail panel as a form: a packet's title and notes. */
    private void showPacketForm(Node n) {
        javafx.scene.control.TextField titleField = new javafx.scene.control.TextField(title(n));
        javafx.scene.control.TextArea notesArea = new javafx.scene.control.TextArea(n.packetNotes == null ? "" : n.packetNotes);
        notesArea.setWrapText(true);
        notesArea.setPrefRowCount(10);

        int selected = timelineList.getSelectionModel().getSelectedIndex();
        Button ok = new Button(QTraceI18n.t("graph.edit.apply"));
        styleButton(ok);
        ok.setOnAction(ev -> edit(() -> {
            editing.renamePacket(n.index, titleField.getText());
            editing.setPacketNotes(n.index, notesArea.getText());
        }, selected));

        detailBox.getChildren().setAll(
            sectionTitle(QTraceI18n.t("graph.edit.packet.edit")),
            sectionTitle(QTraceI18n.t("graph.edit.title")), titleField,
            sectionTitle(QTraceI18n.t("graph.detail.notes")), notesArea,
            formButtons(ok));
    }

    /** OK beside a Cancel that answers to Esc and puts the detail back. */
    private HBox formButtons(Button ok) {
        Button cancel = new Button(QTraceI18n.t("graph.edit.cancel"));
        styleButton(cancel);
        cancel.setCancelButton(true);
        cancel.setOnAction(ev -> {
            VersionTimeline.Entry sel = timelineList.getSelectionModel().getSelectedItem();
            if (sel == null || sel.sessionIndex() >= nodes.size()) { showEmptyDetail(); return; }
            Node n = nodes.get(sel.sessionIndex());
            if (sel.kind() == VersionTimeline.Kind.STEP) showStepDetail(sel, n); else showDetail(n);
        });
        HBox box = new HBox(8, ok, cancel);
        box.setPadding(new Insets(10, 0, 0, 0));
        return box;
    }

    /**
     * A packet: what its author wrote, then — set apart, never editable — what the stamp of the
     * session it comes from said. The author is the certificate holder, not a field.
     */
    private void showPacketDetail(Node n) {
        detailBox.getChildren().clear();
        detailBox.getChildren().add(sectionTitle("#" + (n.index + 1) + " — " + title(n)));
        addBadgeRow(n);
        detailBox.getChildren().add(muted(QTraceI18n.t("graph.edit.author.hint")));
        detailBox.getChildren().add(kv(QTraceI18n.t("graph.edit.packet.holds"), QTraceI18n.f("graph.edit.packet.count", n.steps)));
        if (n.sideRecords > 0)
            detailBox.getChildren().add(muted(QTraceI18n.f("graph.edit.packet.side.hint", n.sideRecords)));

        if (n.packetNotes != null && !n.packetNotes.isBlank()) {
            detailBox.getChildren().add(sectionTitle(QTraceI18n.t("graph.detail.notes")));
            Label notes = new Label(n.packetNotes);
            notes.setWrapText(true);
            notes.setTextFill(Color.web(TEXT_SUB));
            notes.setFont(Font.font("System", 11));
            detailBox.getChildren().add(notes);
        }

        detailBox.getChildren().add(sectionTitle(QTraceI18n.t("graph.edit.origin")));
        if (n.origin == null) {
            detailBox.getChildren().add(muted(QTraceI18n.t("graph.edit.origin.none")));
        } else {
            JsonObject o = n.origin;
            boolean stamped = o.has("stamped") && o.get("stamped").getAsBoolean();
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.validation"),
                QTraceI18n.t(stamped ? "graph.detail.stamped" : "graph.detail.unstamped")));
            originRow(o, "user", "graph.edit.origin.recorded");
            if (o.has("validator"))
                detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.validator"),
                    (o.has("signed") && o.get("signed").getAsBoolean() ? "✓ " : "") + str(o, "validator", "")));
            originRow(o, "confidence", "graph.detail.confidence");
            originRow(o, "classifier_fidelity", "graph.detail.fidelity");
            originRow(o, "scope", "graph.detail.scope");
            String date = str(o, "date", str(o, "exported_at", null));
            if (date != null) detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.date"), dateShort(date)));
            String hash = str(o, "image_hash", null);
            if (hash != null && hash.length() >= 12)
                detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.imagehash"), hash.substring(0, 12) + "…"));
            detailBox.getChildren().add(muted(QTraceI18n.t("graph.edit.origin.hint")));
        }

        if (editing == null) return;
        Button editBtn = new Button(QTraceI18n.t("graph.edit.edit"));
        styleButton(editBtn);
        editBtn.setOnAction(ev -> showPacketForm(n));
        HBox actions = new HBox(8, editBtn);
        actions.setPadding(new Insets(10, 0, 0, 0));
        detailBox.getChildren().add(actions);
    }

    private void originRow(JsonObject origin, String field, String labelKey) {
        String v = str(origin, field, null);
        if (v != null && !v.isBlank()) detailBox.getChildren().add(kv(QTraceI18n.t(labelKey), v));
    }

    // ── Detail panel ───────────────────────────────────────────────────────────

    private void showEmptyDetail() {
        detailBox.getChildren().setAll(
            sectionTitle(QTraceI18n.t("graph.detail.title")),
            muted(QTraceI18n.t("graph.detail.hint")));
    }

    private void showDetail(Node n) {
        if (n.packet) { showPacketDetail(n); return; }
        detailBox.getChildren().clear();
        detailBox.getChildren().add(sectionTitle("#" + (n.index + 1) + " — " + title(n)));

        addBadgeRow(n);
        detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.validation"),
            QTraceI18n.t(n.stamped ? "graph.detail.stamped" : n.live ? "graph.detail.live"
                : n.covered ? "graph.detail.covered" : "graph.detail.unstamped")));
        if (n.validator != null)
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.validator"),
                (n.signed ? "✓ " : "") + n.validator));
        if (n.confidence != null)
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.confidence"), n.confidence));
        if (n.fidelity != null)
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.fidelity"), n.fidelity));
        if (n.scope != null && !n.scope.isBlank())
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.scope"), n.scope));
        detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.branch"), n.branch));
        detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.date"), dateShort(n.exportedAt)));
        if (n.imageHashShort != null)
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.imagehash"), n.imageHashShort));

        // "Who did what" — categorized actions of this commit's contributor.
        detailBox.getChildren().add(sectionTitle(QTraceI18n.t("graph.detail.actions")));
        boolean any = false;
        if (n.contributions != null && n.contributions.has("actions")
                && n.contributions.get("actions").isJsonObject()) {
            JsonObject acts = n.contributions.getAsJsonObject("actions");
            for (String k : acts.keySet()) {
                detailBox.getChildren().add(bullet(k + "  ×" + acts.get(k).getAsInt()));
                any = true;
            }
        }
        if (!any) detailBox.getChildren().add(muted(QTraceI18n.t("graph.detail.noactions")));

        if (n.preTracking > 0)
            detailBox.getChildren().add(muted(QTraceI18n.t("graph.detail.inherited")
                + " : " + n.preTracking));

        if (n.notes != null && !n.notes.isBlank()) {
            detailBox.getChildren().add(sectionTitle(QTraceI18n.t("graph.detail.notes")));
            Label notes = new Label(n.notes);
            notes.setWrapText(true);
            notes.setTextFill(Color.web(TEXT_SUB));
            notes.setFont(Font.font("System", 11));
            detailBox.getChildren().add(notes);
        }
    }

    private void addBadgeRow(Node n) {
        Label badge = new Label(initials(n.contributor));
        badge.setMinSize(26, 26);
        badge.setAlignment(Pos.CENTER);
        Color c = contributorColor(n.contributor);
        badge.setStyle("-fx-background-radius: 13; -fx-background-color: " + toHex(c) + ";"
            + "-fx-text-fill: " + BG_BASE + "; -fx-font-weight: bold; -fx-font-size: 10;");
        Label name = new Label(n.contributor);
        name.setTextFill(Color.web(TEXT_MAIN));
        name.setFont(Font.font("System", FontWeight.BOLD, 12));
        HBox row = new HBox(8, badge, name);
        row.setAlignment(Pos.CENTER_LEFT);
        detailBox.getChildren().add(row);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────--

    private Node nodeAt(double x, double y) {
        for (Node n : nodes) {
            double dx = x - n.cx, dy = y - n.cy;
            if (dx * dx + dy * dy <= NODE_R * NODE_R) return n;
        }
        return null;
    }

    private static String title(Node n) {
        // Commit title is the stamp notes, never the scope.
        if (n.notes != null && !n.notes.isBlank()) return n.notes;
        return "Session #" + (n.index + 1);
    }

    private Color nodeColor(Node n) {
        if ("COMPROMISED".equalsIgnoreCase(n.fidelity)) return Color.web(RED);
        if ("DEGRADED".equalsIgnoreCase(n.fidelity))    return Color.web(PEACH);
        if (n.confidence == null)                       return Color.web(TEXT_MUTED);
        return switch (n.confidence.toLowerCase()) {
            case "high"   -> Color.web(GREEN);
            case "medium" -> Color.web(PEACH);
            case "low"    -> Color.web(RED);
            default        -> Color.web(BLUE);
        };
    }

    private static Color contributorColor(String name) {
        if (name == null || name.isBlank()) return Color.web("#6c7086");
        double hue = Math.abs(name.hashCode()) % 360;
        return Color.hsb(hue, 0.55, 0.85);
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(parts[0].charAt(0)));
        if (parts.length > 1) sb.append(Character.toUpperCase(parts[parts.length - 1].charAt(0)));
        return sb.toString();
    }

    /** "yyyy-MM-dd HH:mm" in local time — same clock as the timeline's HH:mm:ss column. */
    private static String dateShort(String iso) {
        if (iso == null || iso.length() < 16) return iso == null ? "" : iso;
        try {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(java.time.Instant.parse(iso).atZone(ZoneId.systemDefault()));
        } catch (Exception e) {
            return iso.substring(0, 10) + " " + iso.substring(11, 16);
        }
    }

    private static String ellipsis(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String str(JsonObject o, String key, String def) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : def;
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
            (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    private Label sectionTitle(String t) {
        Label l = new Label(t);
        l.setTextFill(Color.web(BLUE));
        l.setFont(Font.font("System", FontWeight.BOLD, 12));
        l.setPadding(new Insets(8, 0, 2, 0));
        return l;
    }

    private Label kv(String k, String v) {
        Label l = new Label(k + " : " + v);
        l.setTextFill(Color.web(TEXT_SUB));
        l.setFont(Font.font("System", 11));
        l.setWrapText(true);
        return l;
    }

    private Label bullet(String s) {
        Label l = new Label("• " + s);
        l.setTextFill(Color.web(TEXT_MAIN));
        l.setFont(Font.font("System", 11));
        return l;
    }

    private Label muted(String s) {
        Label l = new Label(s);
        l.setTextFill(Color.web(TEXT_MUTED));
        l.setFont(Font.font("System", 11));
        l.setWrapText(true);
        return l;
    }

    private void styleButton(Button b) {
        b.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-text-fill: " + TEXT_MAIN + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4;"
            + "-fx-cursor: hand; -fx-padding: 4 10 4 10;");
    }
}
