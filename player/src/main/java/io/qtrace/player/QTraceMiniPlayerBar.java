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
import io.qtrace.ViewerOverlay;
import io.qtrace.player.ext.Compat;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Popup;
import qupath.lib.gui.QuPathGUI;

/**
 * The minimal replay player: a compact pill floating over QuPath's viewers (see
 * {@link ViewerOverlay}), dragged anywhere over the image by its ⠿ handle — the position is
 * remembered across sessions:
 *
 * <pre>
 *  ⠿ 📂 Open ▾  ▶  ▶│   ●  Current ▾  ⤢  ✕
 *  ⏳ Step 3/12 · Cell detection — running…
 * </pre>
 *
 * A view over a {@link QTraceReplayPlayer} built but not shown — same engine, same state:
 * ⤢ shows that Player's window (playlist, targets, log) without losing the position.
 */
final class QTraceMiniPlayerBar {

    private static final String BG       = "#1e1e2e";
    private static final String BORDER   = "#313244";
    private static final String TEXT     = "#cdd6f4";
    private static final String TEXT_SUB = "#a6adc8";
    private static final String MUTED    = "#6c7086";
    private static final String BLUE     = "#89b4fa";
    private static final String GREEN    = "#a6e3a1";
    private static final String RED      = "#f38ba8";

    private static final double MIN_WIDTH = 360;  // below this the controls no longer fit on one line
    private static final double EDGE = 6;         // px from the left/right border that resize the pill
    private static final double MARGIN = 12;   // kept between the pill and the viewers' edges
    // Where the pill sits, as fractions of the free space (0,0 = top-left, 0.5,1 = bottom centre).
    // The node the pill has always used (it lived in io.qtrace): a user's position is kept.
    private static final java.util.prefs.Preferences PREFS =
        java.util.prefs.Preferences.userRoot().node("/io/qtrace");
    private static final String PREF_X = "miniPlayerX", PREF_Y = "miniPlayerY", PREF_W = "miniPlayerWidth";

    private static QTraceMiniPlayerBar current; // one per QuPath window

    private final QuPathGUI qupath;
    private final QTraceReplayPlayer player;
    private final VBox root;      // the pill
    private final javafx.scene.layout.Pane layer; // full-size, click-through; the pill sits in it
    private ViewerOverlay dock;
    private double fx = PREFS.getDouble(PREF_X, 0.5), fy = PREFS.getDouble(PREF_Y, 1.0);
    private double dragDx, dragDy;
    private double pillWidth = Math.max(MIN_WIDTH, PREFS.getDouble(PREF_W, 400));
    private int resizeSide;               // -1 left edge, +1 right edge, 0 not resizing
    private double resizeStartX, resizeStartW, resizeStartLeft;
    private javafx.scene.control.ProgressIndicator spinner;
    private javafx.scene.control.ProgressIndicator playSpinner; // over playBtn while the target image opens

    private Button playBtn, stepBtn, closeBtn;
    private MenuButton openBtn, targetBtn;
    private Label compatDot, statusLabel;
    private boolean allImages;

    /** What ↻ Replay does when a player already exists. */
    enum Again { CLOSE, FOCUS, SHOW_WINDOW }

    /**
     * ↻ Replay is a toggle: clicked again, it takes the idle pill away. A replay that is
     * running (or loading) is never closed that way, and the full Player window — the pill's
     * ⤢, or the only player when the pill couldn't dock — is brought back instead.
     */
    static Again onReplayAgain(boolean docked, boolean windowShowing, boolean running) {
        if (!docked || windowShowing) return Again.SHOW_WINDOW;
        return running ? Again.FOCUS : Again.CLOSE;
    }

    /**
     * Opens the player on {@code source}, docking the bar first when there is none. A player in
     * the middle of a run is left alone: it is brought forward, and the source is not loaded.
     * FX thread.
     */
    static void openSource(QuPathGUI qupath, ActionLogger logger, String source) {
        if (source == null || source.isBlank()) return;
        if (current == null) open(qupath, logger);
        if (current == null) return;
        if (current.player.isPlaying() || current.player.isBusy()) {
            current.root.requestFocus();
            return;
        }
        current.player.openFromMiniPlayer(source);
    }

    /** ↻ Replay: docks the bar — or, when one is already there, closes it (see {@link #onReplayAgain}). FX thread. */
    static void open(QuPathGUI qupath, ActionLogger logger) {
        // Only one player at a time.
        if (current != null) {
            boolean running = current.player.isPlaying() || current.player.isBusy();
            switch (onReplayAgain(current.dock != null, current.player.isWindowShowing(), running)) {
                case CLOSE -> current.close();
                case FOCUS -> current.root.requestFocus();
                case SHOW_WINDOW -> current.player.show();
            }
            return;
        }
        QTraceMiniPlayerBar bar = new QTraceMiniPlayerBar(qupath, new QTraceReplayPlayer(qupath, logger));
        current = bar;
        bar.dock = ViewerOverlay.dock(qupath, bar.layer);
        if (bar.dock == null) {
            // Unknown QuPath layout — never leave the user without a player: open the window,
            // as a normal one (no ⤡ back to a pill that doesn't exist), still the only player.
            bar.player.setKeepAliveOnClose(false);
            bar.player.setOnDisposed(() -> { if (current == bar) current = null; });
            bar.player.show();
        }
    }

    // Package-private (not private) so the screenshot harness — same io.qtrace package, see
    // PlayerScreenshotHarness — can build the pill around a Player without docking it in QuPath.
    QTraceMiniPlayerBar(QuPathGUI qupath, QTraceReplayPlayer player) {
        this.qupath = qupath;
        this.player = player;
        player.ensureBuilt();
        player.setKeepAliveOnClose(true);
        root = buildUi();
        layer = new javafx.scene.layout.Pane(root);
        layer.setPickOnBounds(false); // only the pill catches clicks — the image stays usable
        layer.widthProperty().addListener((o, a, b) -> place());
        layer.heightProperty().addListener((o, a, b) -> place());
        root.widthProperty().addListener((o, a, b) -> place());
        root.heightProperty().addListener((o, a, b) -> place());
        player.addStateListener(this::refresh);
        refresh();
    }

    /** Puts the pill at its remembered spot, inside the viewers whatever their size. */
    private void place() {
        double maxW = Math.max(MIN_WIDTH, layer.getWidth() - 2 * MARGIN);
        if (layer.getWidth() > 0 && pillWidth > maxW) setPillWidth(maxW);
        double freeW = Math.max(0, layer.getWidth() - root.getWidth() - 2 * MARGIN);
        double freeH = Math.max(0, layer.getHeight() - root.getHeight() - 2 * MARGIN);
        root.relocate(MARGIN + fx * freeW, MARGIN + fy * freeH);
        // Menus open towards the middle of the image, never past its edge.
        Side side = fy < 0.5 ? Side.BOTTOM : Side.TOP;
        if (openBtn != null) openBtn.setPopupSide(side);
        if (targetBtn != null) targetBtn.setPopupSide(side);
    }

    private void startDrag(javafx.scene.input.MouseEvent e) {
        dragDx = e.getSceneX() - root.getLayoutX();
        dragDy = e.getSceneY() - root.getLayoutY();
    }

    private void drag(javafx.scene.input.MouseEvent e) {
        double freeW = Math.max(1, layer.getWidth() - root.getWidth() - 2 * MARGIN);
        double freeH = Math.max(1, layer.getHeight() - root.getHeight() - 2 * MARGIN);
        fx = clamp((e.getSceneX() - dragDx - MARGIN) / freeW);
        fy = clamp((e.getSceneY() - dragDy - MARGIN) / freeH);
        place();
    }

    /** Left/right borders widen or narrow the pill; the width is remembered like the position. */
    private void installEdgeResize(VBox box) {
        box.setOnMouseMoved(e -> box.setCursor(edgeAt(box, e.getX()) != 0 ? javafx.scene.Cursor.H_RESIZE : null));
        box.setOnMouseExited(e -> { if (resizeSide == 0) box.setCursor(null); });
        box.setOnMousePressed(e -> {
            resizeSide = edgeAt(box, e.getX());
            resizeStartX = e.getScreenX();
            resizeStartW = box.getWidth();
            resizeStartLeft = box.getLayoutX();
        });
        box.setOnMouseDragged(e -> {
            if (resizeSide == 0) return;
            double maxW = Math.max(MIN_WIDTH, layer.getWidth() - 2 * MARGIN);
            double dx = e.getScreenX() - resizeStartX;
            double w = Math.max(MIN_WIDTH, Math.min(maxW, resizeStartW + resizeSide * dx));
            // Dragging the left border keeps the right one still, and vice versa.
            double left = resizeSide < 0 ? resizeStartLeft + (resizeStartW - w) : resizeStartLeft;
            left = Math.max(MARGIN, Math.min(layer.getWidth() - MARGIN - w, left));
            setPillWidth(w);
            double freeW = layer.getWidth() - w - 2 * MARGIN;
            fx = freeW > 0 ? clamp((left - MARGIN) / freeW) : 0.5;
            place();
            e.consume();
        });
        box.setOnMouseReleased(e -> {
            if (resizeSide == 0) return;
            resizeSide = 0;
            PREFS.putDouble(PREF_W, pillWidth);
            endDrag();
        });
    }

    private int edgeAt(VBox box, double x) {
        if (x <= EDGE) return -1;
        if (x >= box.getWidth() - EDGE) return 1;
        return 0;
    }

    private void setPillWidth(double w) {
        pillWidth = w;
        root.setPrefWidth(w);
        root.setMaxWidth(w);
        root.resize(w, root.getHeight());
    }

    private void endDrag() {
        PREFS.putDouble(PREF_X, fx);
        PREFS.putDouble(PREF_Y, fy);
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    private VBox buildUi() {
        MenuItem local = new MenuItem("Local .qtrace file…");
        local.setOnAction(e -> player.browseFromMiniPlayer());
        MenuItem remote = new MenuItem("qtrace.ca ID or share link…");
        openBtn = new MenuButton("📂 Open", null, local, remote);
        remote.setOnAction(e -> showIdPopup(openBtn));
        // Then one entry per module source (e.g. "Training…").
        for (io.qtrace.PlayerSource source : QTraceReplayPlayer.moduleSources()) {
            MenuItem item = new MenuItem(source.label());
            item.setOnAction(e -> player.chooseFromSource(source));
            openBtn.getItems().add(item);
        }
        style(openBtn);
        darkPopup(openBtn);
        openBtn.setTooltip(new Tooltip("Open a replay"));

        playBtn = button("▶", "Play / pause", e -> { applyTargets(); player.playFromMiniPlayer(); });
        stepBtn = button("▶│", "Run the next instruction", e -> { applyTargets(); player.stepFromMiniPlayer(); });

        // Covers up ▶/⏸ with a spinner while the target image opens — after Play, before the
        // first instruction actually starts (runningRow is still null then; see refresh()).
        playSpinner = new javafx.scene.control.ProgressIndicator();
        playSpinner.setPrefSize(12, 12);
        playSpinner.setMaxSize(12, 12);
        playSpinner.setStyle("-fx-progress-color: " + BLUE + ";");
        playSpinner.setVisible(false);
        playSpinner.setMouseTransparent(true);
        // Stays managed (unlike the status-row spinner): inside this StackPane, an unmanaged
        // child is skipped by the layout pass and never gets centred over playBtn — it would
        // just sit pinned at the pane's top-left corner instead of covering the icon.
        StackPane playBox = new StackPane(playBtn, playSpinner);

        compatDot = new Label("●");
        compatDot.setFont(Font.font("System", 14));
        compatDot.setStyle("-fx-cursor: hand;");
        compatDot.setOnMouseClicked(e -> showPopup(compatDot, player.precheckSummary()));

        targetBtn = new MenuButton();
        MenuItem currentImage = new MenuItem("Current image");
        currentImage.setOnAction(e -> { allImages = false; refresh(); });
        MenuItem everyImage = new MenuItem("All project images");
        everyImage.setOnAction(e -> { allImages = true; refresh(); });
        targetBtn.getItems().setAll(currentImage, everyImage);
        style(targetBtn);
        darkPopup(targetBtn);
        targetBtn.setTooltip(new Tooltip("Image(s) the replay is applied to"));

        Button expandBtn = button("⤢", "Open the full Player (instructions, targets, log)", e -> player.show());
        closeBtn = button("✕", "Close the player", e -> close());

        Label handle = new Label("⠿");
        handle.setFont(Font.font("System", 16));
        handle.setTextFill(Color.web(MUTED));
        handle.setCursor(javafx.scene.Cursor.MOVE);
        handle.setTooltip(new Tooltip("Drag to move the player"));
        handle.setOnMousePressed(this::startDrag);
        handle.setOnMouseDragged(this::drag);
        handle.setOnMouseReleased(e -> endDrag());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(6, handle, openBtn, playBox, stepBtn, spacer, compatDot, targetBtn, expandBtn, closeBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label();
        statusLabel.setFont(Font.font("System", 11));
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setMinWidth(0);
        statusLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);

        // Spins while the source .qtrace is being downloaded / parsed.
        spinner = new javafx.scene.control.ProgressIndicator();
        spinner.setPrefSize(12, 12);
        spinner.setMaxSize(12, 12);
        spinner.setStyle("-fx-progress-color: " + BLUE + ";");
        spinner.setVisible(false);
        spinner.setManaged(false);
        HBox statusRow = new HBox(6, spinner, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(4, controls, statusRow);
        box.setPadding(new Insets(6, 10, 7, 10));
        box.setMinWidth(MIN_WIDTH);
        box.setPrefWidth(pillWidth);
        box.setMaxWidth(pillWidth);
        installEdgeResize(box);
        box.setStyle("-fx-background-color: rgba(30,30,46,0.94); -fx-background-radius: 10;"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 10;"
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 12, 0, 0, 3);");
        return box;
    }

    /** Re-renders from the Player's state — on every state change. FX thread. */
    private void refresh() {
        MiniPlayerStatus st = player.status();
        boolean loaded = player.isLoaded();
        boolean playing = player.isPlaying();
        boolean busy = player.isBusy();

        playBtn.setText(playing ? "⏸" : "▶");
        playBtn.setDisable(!loaded || (!playing && (busy || player.isFinished())));
        // The gap between clicking ▶ and the first instruction actually starting (the target
        // image opening in the viewer) — runningRow is still null then, so stepTitle() is null.
        boolean openingImage = playing && st.phase() == MiniPlayerStatus.Phase.RUNNING && st.stepTitle() == null;
        playSpinner.setVisible(openingImage);
        playBtn.setOpacity(openingImage ? 0 : 1);
        stepBtn.setDisable(!loaded || playing || busy || player.isFinished());
        closeBtn.setDisable(playing || busy);
        targetBtn.setDisable(!loaded || playing || busy);
        int n = player.projectImageCount();
        targetBtn.setText(allImages && n > 0 ? "All (" + n + ")" : "Current");

        Compat compat = player.compatOverall();
        compatDot.setVisible(compat != null);
        if (compat != null) {
            compatDot.setTextFill(Color.web(compat.color));
            Tooltip.install(compatDot, new Tooltip(compat.label + " — click for details"));
        }

        statusLabel.setText(st.text());
        statusLabel.setTooltip(new Tooltip(st.text())); // full text when the pill cuts it
        boolean loading = st.phase() == MiniPlayerStatus.Phase.LOADING;
        spinner.setVisible(loading);
        spinner.setManaged(loading);
        statusLabel.setTextFill(Color.web(switch (st.phase()) {
            case DONE, READY -> GREEN;
            case FAILED, LOAD_ERROR -> RED;
            case RUNNING, LOADING -> BLUE;
            default -> TEXT_SUB;
        }));
        statusLabel.setFont(Font.font("System",
            st.phase() == MiniPlayerStatus.Phase.DONE ? FontWeight.BOLD : FontWeight.NORMAL, 11));
    }

    /** "Current image" means the one open now — resolved at the click, not when it was chosen. */
    private void applyTargets() {
        player.useTargets(allImages && player.projectImageCount() > 0);
    }

    private void showIdPopup(MenuButton anchor) {
        TextField field = new TextField();
        field.setPromptText("qtc_… ID or qtrace.ca share link — Enter");
        field.setPrefColumnCount(34);
        Popup popup = popupWith(field);
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !field.getText().isBlank()) {
                popup.hide();
                player.openFromMiniPlayer(field.getText());
            } else if (e.getCode() == KeyCode.ESCAPE) {
                popup.hide();
            }
        });
        showAbove(popup, anchor);
        Platform.runLater(field::requestFocus);
    }

    private void showPopup(javafx.scene.Node anchor, String text) {
        Label label = new Label(text);
        label.setTextFill(Color.web(TEXT));
        label.setFont(Font.font("System", 11));
        showAbove(popupWith(label), anchor);
    }

    private static Popup popupWith(javafx.scene.Node content) {
        VBox box = new VBox(content);
        box.setPadding(new Insets(8));
        box.setStyle("-fx-background-color: " + BG + "; -fx-border-color: " + BORDER + "; -fx-border-radius: 4;");
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.getContent().add(box);
        return popup;
    }

    /** Popups open towards the middle of the image: above the pill when it sits low, below when high. */
    private void showAbove(Popup popup, javafx.scene.Node anchor) {
        var b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor, b.getMinX(), b.getMaxY() + 4);
        if (fy >= 0.5) popup.setY(b.getMinY() - popup.getHeight() - 4);
    }

    /** The pill itself — for the screenshot harness. */
    VBox pill() { return root; }

    private void close() {
        if (dock != null) dock.undock();
        player.dispose();
        current = null;
    }

    private static Button button(String text, String tip, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button b = new Button(text);
        b.setOnAction(action);
        b.setTooltip(new Tooltip(tip));
        style(b);
        return b;
    }

    private static void style(javafx.scene.control.Control c) {
        // -fx-text-base-color / -fx-mark-color: QuPath's modena styles a MenuButton's label and
        // arrow from these looked-up colours, not from -fx-text-fill — without them "Open" and
        // "Current" rendered grey-on-dark, nearly invisible.
        c.setStyle("-fx-background-color: #181825; -fx-text-fill: " + TEXT + "; -fx-text-base-color: " + TEXT + ";"
            + "-fx-mark-color: " + TEXT_SUB + "; -fx-border-color: " + BORDER + ";"
            + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 2 7 2 7;");
    }

    /**
     * Dark popup for a MenuButton of the bar. The light text colour set by {@link #style} is a
     * looked-up colour, so the popup's entries inherit it — on modena's default white popup they
     * were grey on white, unreadable. The popup itself is only reachable through its entries.
     */
    private static void darkPopup(MenuButton button) {
        button.showingProperty().addListener((obs, was, showing) -> {
            if (!showing || button.getItems().isEmpty()) return;
            javafx.scene.control.ContextMenu popup = button.getItems().get(0).getParentPopup();
            if (popup != null) popup.setStyle(POPUP_STYLE);
        });
    }

    private static final String POPUP_STYLE =
        "-fx-background-color: #181825; -fx-border-color: " + BORDER + "; -fx-border-radius: 4;"
      + "-fx-background-radius: 4; -fx-padding: 4 0 4 0; -fx-text-base-color: " + TEXT + ";"
      // the hovered entry: a lighter band, same light text
      + "-fx-selection-bar: " + BORDER + "; -fx-focused-text-base-color: " + TEXT + ";";
}
