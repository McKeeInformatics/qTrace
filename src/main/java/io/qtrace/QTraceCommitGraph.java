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
    // Virtualized: real traces carry ~900 steps; one node tree per row made every click relayout
    // them all (~1 s per click). A ListView only builds the rows on screen.
    private final ListView<VersionTimeline.Entry> timelineList;
    private final Label      timelineFooter;
    private final Map<Integer, Integer> milestoneIndex = new HashMap<>();   // session → list index
    private List<VersionTimeline.Entry> allEntries = List.of();
    private JsonObject timelineRoot;
    private Integer shownSession;   // node clicked in the graph: only its steps are listed; null = all

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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, headerLabel, spacer, openBtn);
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
        timelineList.getSelectionModel().selectedItemProperty().addListener((o, was, e) -> {
            if (e == null || e.sessionIndex() >= nodes.size()) return;
            Node n = nodes.get(e.sessionIndex());
            if (e.kind() == VersionTimeline.Kind.STEP) showStepDetail(e, n); else showDetail(n);
            redraw(n);
        });
        VBox.setVgrow(timelineList, Priority.ALWAYS);

        timelineFooter = new Label("");
        timelineFooter.setFont(Font.font(MONO, 10));
        timelineFooter.setTextFill(Color.web(TEXT_MUTED));
        timelineFooter.setMaxWidth(Double.MAX_VALUE);
        timelineFooter.setPadding(new Insets(6, 14, 6, 14));
        timelineFooter.setStyle("-fx-background-color: " + BG_SURFACE + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 1 0 0 0;");

        VBox timelinePane = new VBox(timelineTitle, timelineList, timelineFooter);
        timelinePane.setStyle("-fx-background-color: " + BG_BASE + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 1 0 0 0;");

        SplitPane split = new SplitPane(scroll, timelinePane);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.34);
        split.setStyle("-fx-background-color: " + BG_BASE + "; -fx-box-border: transparent;");

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(split);
        root.setRight(detailBox);
        root.setStyle("-fx-background-color: " + BG_BASE + ";");

        stage.setScene(new Scene(root, 1180, 760));
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
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(".qtrace", "*.qtrace"));
        File f = fc.showOpenDialog(stage);
        if (f != null) load(f);
    }

    /** {@code file} null: no .qtrace yet — only the host's capture in progress. */
    private void load(File file) {
        loadedFile = file;
        nodes.clear();
        JsonObject loaded = null;
        try {
            JsonObject root = file != null
                ? JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject() : new JsonObject();
            loaded = root;
            if (!root.has("sessions") || !root.get("sessions").isJsonArray()) root.add("sessions", new JsonArray());
            JsonArray sessions = root.getAsJsonArray("sessions");
            JsonObject live = host != null ? host.liveSession(file) : null;
            if (live != null) sessions.add(live);

            String imgName = root.has("image") && root.getAsJsonObject("image").has("name")
                ? root.getAsJsonObject("image").get("name").getAsString()
                : file != null ? file.getName() : live != null ? str(live, "image_name", "") : "";
            headerLabel.setText("⑃  " + imgName);

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
        if (loadedFile == null && current != null) loadedFile = current;
        refreshDelay.setOnFinished(e -> {
            int size = timelineList.getItems().size();
            int top = firstVisibleRow();
            boolean atEnd = size == 0 || lastVisibleRow() >= size - 1;   // reading the latest: keep following
            reload(timelineList.getSelectionModel().getSelectedIndex(), top, atEnd);
        });
        refreshDelay.playFromStart();
    }

    /**
     * Another image was opened in QuPath: the window switches to its history ({@code current}:
     * its .qtrace, null when it has none yet — then only its capture in progress, if any). FX thread.
     */
    public void showImage(File current) {
        if (!stage.isShowing()) return;
        refreshDelay.stop();
        load(current);
    }

    /** Reads the file and the capture again; the session shown, the selection and the scroll stay. */
    private void reload(int selected, int top, boolean followEnd) {
        Integer session = shownSession;
        load(loadedFile);
        if (session != null && session < nodes.size()) {
            shownSession = session;
            showTimeline();
        }
        int size = timelineList.getItems().size();
        if (selected >= 0 && selected < size) timelineList.getSelectionModel().select(selected);
        else if (session != null && session < nodes.size()) redraw(nodes.get(session));
        if (followEnd) timelineList.scrollTo(size - 1);
        else if (top >= 0 && top < size) timelineList.scrollTo(top);
    }

    private int firstVisibleRow() {
        return timelineList.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow
            && flow.getFirstVisibleCell() != null ? flow.getFirstVisibleCell().getIndex() : -1;
    }

    private int lastVisibleRow() {
        return timelineList.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow
            && flow.getLastVisibleCell() != null ? flow.getLastVisibleCell().getIndex() : -1;
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
            n.cx = ORIGIN_X + (nodes.size() - 1 - n.index) * X_GAP;   // latest session first, on the left
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
            g.strokeLine(b.cx + NODE_R, b.cy, a.cx - NODE_R, a.cy);   // b, the later one, is on the left
        }

        for (Node n : nodes) drawNode(g, n, n == selected);
    }

    private void drawNode(GraphicsContext g, Node n, boolean selected) {
        Color nodeColor = nodeColor(n);

        if (selected) {
            g.setStroke(Color.web(BLUE));
            g.setLineWidth(3);
            g.strokeOval(n.cx - NODE_R - 4, n.cy - NODE_R - 4, (NODE_R + 4) * 2, (NODE_R + 4) * 2);
        }
        if (n.stamped) {
            g.setFill(nodeColor);
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
        g.setFill(Color.web(n.stamped ? BG_BASE : TEXT_MUTED));
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
        List<VersionTimeline.Entry> entries = VersionTimeline.shown(allEntries, shownSession);
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).kind() != VersionTimeline.Kind.STEP) milestoneIndex.put(entries.get(i).sessionIndex(), i);
        timelineList.getItems().setAll(entries);
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
        timelineList.scrollTo(entries.size() - 1); // latest work first in view
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
        if (host != null && e.replayable()) {
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
    }

    // ── Detail panel ───────────────────────────────────────────────────────────

    private void showEmptyDetail() {
        detailBox.getChildren().setAll(
            sectionTitle(QTraceI18n.t("graph.detail.title")),
            muted(QTraceI18n.t("graph.detail.hint")));
    }

    private void showDetail(Node n) {
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
