package io.qtrace;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The mini-panel: the panel's buttons as a compact column floating over QuPath's viewers (see
 * {@link ViewerOverlay}), against their right edge by default and dragged anywhere over the
 * image by its ⠿ handle — the position is remembered across sessions:
 *
 * <pre>
 *  ⠿ ✕  ●  [⚠]  Stamp │ Upload Replay │ Version(s) Report │ Dashboard Import Reset │ ⚙ ⤢
 * </pre>
 *
 * A view over a {@link QTracePanel} built but not shown — same state, same actions: ⤢ shows
 * that panel's window (counters, integrity alert, activity log).
 */
public final class QTraceMiniPanel {

    private static final double MARGIN = 8; // kept between the column and the viewers' edges
    // The top row (handle + close) sets the column's width; icons are sized to fill it.
    private static final double ICON = 26, GLYPH = 22;
    private static final Preferences PREFS = Preferences.userNodeForPackage(QTraceMiniPanel.class);
    private static final String PREF_X = "miniPanelX", PREF_Y = "miniPanelY";

    private final QuPathGUI qupath;
    private final QTraceController controller;
    private final QTracePanel panel;
    private final VBox column;
    private final Pane layer; // full-size, click-through; the column sits in it
    private ViewerOverlay overlay;
    // Where the column sits, as fractions of the free space (1, 0.5 = right edge, vertically centred).
    private double fx = PREFS.getDouble(PREF_X, 1.0), fy = PREFS.getDouble(PREF_Y, 0.5);
    private double dragDx, dragDy;

    private Circle captureDot;
    private Node pauseIcon, startIcon;
    private StackPane statusBox;
    private Timeline captureBlink;
    private Label integrityBadge;
    private Button stampBtn, uploadBtn, resetBtn;
    private StackPane uploadSlot; // the Upload button, or a spinner while a push is running
    private Node uploadSpinner;

    /** Docks the column over the viewers; null if QuPath's layout isn't one {@link ViewerOverlay} knows. FX thread. */
    public static QTraceMiniPanel dock(QuPathGUI qupath, QTraceController controller, QTracePanel panel) {
        QTraceMiniPanel mini = new QTraceMiniPanel(qupath, controller, panel);
        mini.overlay = ViewerOverlay.dock(qupath, mini.layer);
        if (mini.overlay == null) return null;
        panel.setKeepAliveOnClose(true);
        return mini;
    }

    /** Public for the screenshot harness, which builds the column without docking it in QuPath. */
    public QTraceMiniPanel(QuPathGUI qupath, QTraceController controller, QTracePanel panel) {
        this.qupath = qupath;
        this.controller = controller;
        this.panel = panel;
        column = new VBox(3);
        column.setAlignment(Pos.TOP_CENTER);
        column.setPadding(new Insets(6, 5, 6, 5));
        column.setStyle("-fx-background-color: rgba(30,30,46,0.94); -fx-background-radius: 10;"
            + "-fx-border-color: " + QTracePanel.BORDER + "; -fx-border-radius: 10;"
            + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 12, 0, 0, 3);");
        layer = new Pane(column);
        layer.setPickOnBounds(false); // only the column catches clicks — the image stays usable
        layer.widthProperty().addListener((o, a, b) -> place());
        layer.heightProperty().addListener((o, a, b) -> place());
        column.widthProperty().addListener((o, a, b) -> place());
        column.heightProperty().addListener((o, a, b) -> place());
        rebuild();
        // A module can load after the column was drawn: its button comes in then.
        PanelExtensions.onChange(QTraceMiniPanel.class, () -> javafx.application.Platform.runLater(this::rebuild));
        panel.addStateListener(this::refresh);
        // Opening or closing a project switches ▶ Start ↔ pause, with or without an image.
        var project = qupath.projectProperty();
        if (project != null) project.addListener((o, a, b) -> refresh());
    }

    public boolean isDocked() { return overlay != null; }

    /**
     * ⤢: the column leaves the image while the full panel is shown — one or the other, never
     * both. The panel itself lives on; {@link #redock()} (the full panel's ⤡) brings the column back.
     */
    public void hideForFullPanel() {
        if (overlay == null) return;
        overlay.undock();
        overlay = null;
        stopBlink();
        panel.setKeepAliveOnClose(false); // closing the full window now closes the panel
    }

    /** ✕: takes the column off the image and closes the panel; "Panel" brings it back. */
    private void close() {
        if (overlay == null) return;
        overlay.undock();
        overlay = null;
        stopBlink();
        panel.setKeepAliveOnClose(false);
        panel.releaseIfHidden();
    }

    /** Puts a closed column back over the viewers; false if QuPath's layout isn't one the overlay knows. */
    public boolean redock() {
        if (overlay != null) return true;
        overlay = ViewerOverlay.dock(qupath, layer);
        if (overlay == null) return false;
        panel.setKeepAliveOnClose(true);
        refresh();
        return true;
    }

    /** The column itself — for the screenshot harness. */
    public VBox column() { return column; }

    /** Rebuilds the buttons — the entitlement decides which groups exist. FX thread. */
    public void rebuild() {
        stopBlink();
        uploadBtn = null;
        uploadSlot = null;
        List<Node> nodes = new ArrayList<>();

        Label handle = new Label("⠿");
        handle.setFont(Font.font("System", 16));
        handle.setTextFill(Color.web(QTracePanel.TEXT_MUTED));
        handle.setCursor(Cursor.MOVE);
        handle.setTooltip(tip("Drag to move the panel"));
        handle.setOnMousePressed(this::startDrag);
        handle.setOnMouseDragged(this::drag);
        handle.setOnMouseReleased(e -> endDrag());

        Button closeBtn = boxed("✕", "Close");
        closeBtn.setOnAction(e -> close());
        javafx.scene.layout.HBox top = new javafx.scene.layout.HBox(4, handle, closeBtn);
        top.setAlignment(Pos.CENTER);
        nodes.add(top);

        // Passive capture status — a red dot (blinking) while recording, grey pause bars when paused.
        captureDot = new Circle(6, Color.web(QTracePanel.RED));
        javafx.scene.shape.Rectangle bar1 = new javafx.scene.shape.Rectangle(3.5, 12, Color.web(QTracePanel.TEXT_MUTED));
        javafx.scene.shape.Rectangle bar2 = new javafx.scene.shape.Rectangle(3.5, 12, Color.web(QTracePanel.TEXT_MUTED));
        javafx.scene.layout.HBox bars = new javafx.scene.layout.HBox(3.5, bar1, bar2);
        bars.setAlignment(Pos.CENTER);
        pauseIcon = bars;
        // ▶ Start — shown instead while no project is open: one click lists the recent projects.
        javafx.scene.shape.Polygon play = new javafx.scene.shape.Polygon(0, 0, 13, 7.5, 0, 15);
        play.setFill(Color.web(QTracePanel.GREEN));
        startIcon = play;
        StackPane dotBox = new StackPane(pauseIcon, captureDot, startIcon);
        statusBox = dotBox;
        // One click: the recent projects. Double-click: straight back to the last project and
        // image — so the single click waits a moment to see whether a second one follows.
        javafx.animation.PauseTransition single = new javafx.animation.PauseTransition(Duration.millis(260));
        // runLater: a dialog can't be shown-and-waited from inside an animation callback.
        single.setOnFinished(e -> javafx.application.Platform.runLater(controller::startWork));
        // With a project open (recording or paused), a double-click closes the project — the
        // counterpart of ▶ Start. QuPath asks about unsaved work, as with its own menu.
        dotBox.setOnMouseClicked(e -> {
            if (!startIcon.isVisible()) {
                if (e.getClickCount() == 2) controller.closeProject();
                return;
            }
            if (e.getClickCount() >= 2) { single.stop(); controller.resumeLastWork(); }
            else single.playFromStart();
        });
        dotBox.setPadding(new Insets(3, 0, 12, 0));
        nodes.add(dotBox);

        // Integrity alert for the open image — hidden unless its stamp no longer holds.
        integrityBadge = new Label();
        integrityBadge.setFont(Font.font("System", 18));
        integrityBadge.setCursor(Cursor.HAND);
        integrityBadge.setOnMouseClicked(e -> {
            Runnable why = panel.integrityOnWhy();
            if (why != null) why.run();
        });
        nodes.add(integrityBadge);

        Color teal = Color.web(QTracePanel.CTA_TEAL);
        stampBtn = button(c -> panel.scaledIcon(panel.iconStamp(teal), ICON), "btn.stamp.caption", teal);
        stampBtn.setOnAction(e -> controller.recordTrace());
        nodes.add(stampBtn);

        // Workspace & Analyse — same rule as the panel's toolbar: only when licensed & active.
        if (QTracePluginManager.isEntitled()) {
            Color workspace = Color.web(QTracePanel.GROUP_WORKSPACE);
            nodes.add(separator());
            uploadBtn = button(icon(panel::iconUpload), "btn.upload.caption", workspace);
            uploadBtn.setOnAction(e -> controller.pushToWorkspace());
            Button replayBtn = button(icon(panel::iconReplay), "btn.replay.caption", workspace);
            replayBtn.setOnAction(e -> controller.openReplayDialog());
            uploadSlot = new StackPane(uploadBtn);
            uploadSpinner = uploadSpinner();
            nodes.add(uploadSlot);
            nodes.add(replayBtn);
            nodes.add(separator());
            Button versionsBtn = button(icon(panel::iconVersions), "btn.versions.caption", workspace);
            versionsBtn.setOnAction(e -> controller.showCommitGraph());
            Button reportBtn = button(icon(panel::iconReport), "btn.report.caption", workspace);
            reportBtn.setOnAction(e -> controller.generateActivityReport());
            nodes.add(versionsBtn);
            addModuleButtons(nodes, PanelExtensions.VERSIONS, workspace);
            nodes.add(reportBtn);
        }

        Color tools = Color.web(QTracePanel.GROUP_TOOLS);
        nodes.add(separator());
        Button dashboardBtn = button(icon(panel::iconDashboard), "btn.dashboard.caption", tools);
        dashboardBtn.setOnAction(e -> controller.showDashboard());
        Button importBtn = button(icon(panel::iconImport), "btn.import.caption", tools);
        importBtn.setOnAction(e -> controller.startBatchExport());
        resetBtn = button(icon(panel::iconReset), "btn.reset.caption", Color.web(QTracePanel.RED));
        resetBtn.setOnAction(e -> panel.confirmReset());
        nodes.add(dashboardBtn);
        addModuleButtons(nodes, PanelExtensions.DASHBOARD, tools);
        nodes.add(importBtn);
        nodes.add(resetBtn);

        Color admin = Color.web(QTracePanel.GROUP_ADMIN);
        nodes.add(separator());
        Button settingsBtn = titled(glyph("⚙"), "Settings", admin);
        settingsBtn.setOnAction(e -> QTraceSettingsDialog.show(qupath));
        // Same glyph and look as the mini-player's ⤢.
        Button expandBtn = boxed("⤢", "Full panel");
        expandBtn.setOnAction(e -> controller.switchToFullPanel());
        // Smaller than the rest: not part of the daily workflow, but always one click away.
        Button reportBtn = titled(QTraceMiniPanel::reportIcon,
            QTraceI18n.t("report.menu").replace("...", ""), admin);
        reportBtn.setOnAction(e -> IssueReportDialog.show(qupath));
        nodes.add(settingsBtn);
        nodes.add(reportBtn);
        // The reduce / enlarge button is always the last one, at the very bottom.
        VBox.setMargin(expandBtn, new Insets(4, 0, 0, 0));
        nodes.add(expandBtn);

        column.getChildren().setAll(nodes);
        refresh();
    }

    /** The modules' buttons that go with a panel button, under it ({@link PanelExtensions}). */
    private void addModuleButtons(List<Node> nodes, String anchor, Color hover) {
        for (PanelExtensions.Entry entry : PanelExtensions.entitled(anchor)) {
            Button b = titled(icon(QTracePanel.moduleIcon(entry)), entry.label(), hover);
            b.setOnAction(e -> QTracePanel.runModuleEntry(entry));
            nodes.add(b);
        }
    }

    /** Re-renders from the panel's state — on every state change. FX thread. */
    private void refresh() {
        boolean recording = panel.isRecordingActive();
        // ▶ Start with no project open; then a red dot while recording, grey pause bars otherwise.
        boolean start = !recording && controller.needsProject();
        startIcon.setVisible(start);
        captureDot.setVisible(recording);
        pauseIcon.setVisible(!recording && !start);
        statusBox.setCursor(start ? Cursor.HAND : null);
        Tooltip.install(statusBox, tip(start ? "Start — click: recent projects · double-click: last project and image"
            : (recording ? "Recording" : "Paused") + " — double-click: close the project"));
        if (recording && captureBlink == null) startBlink();
        else if (!recording) stopBlink();

        StampIntegrity.State state = panel.integrityState();
        boolean alert = state.isAlert();
        integrityBadge.setVisible(alert);
        integrityBadge.setManaged(alert);
        if (alert) {
            boolean corrupted = QTracePanel.integrityCorrupted(state);
            integrityBadge.setText(corrupted ? "⛔" : "⚠");
            integrityBadge.setTextFill(Color.web(corrupted ? QTracePanel.RED : QTracePanel.PEACH));
            integrityBadge.setTooltip(tip(QTracePanel.integrityMessage(state) + " — click for details"));
        }

        // Teal only when it can be clicked; greyed like Upload otherwise (no image, nothing captured).
        boolean canStamp = panel.isStampEnabled();
        if (stampBtn != null && stampBtn.isDisable() == canStamp)
            stampBtn.setGraphic(panel.scaledIcon(panel.iconStamp(
                Color.web(canStamp ? QTracePanel.CTA_TEAL : QTracePanel.TEXT_MUTED)), ICON));
        enable(stampBtn, canStamp);
        enable(uploadBtn, panel.isPushEnabled());
        if (uploadSlot != null) {
            Node shown = panel.isPushInProgress() ? uploadSpinner : uploadBtn;
            if (uploadSlot.getChildren().get(0) != shown) uploadSlot.getChildren().setAll(shown);
        }
        enable(resetBtn, controller.hasActiveImage());
    }

    private static void enable(Button b, boolean enabled) {
        if (b == null) return;
        b.setDisable(!enabled);
        b.setOpacity(enabled ? 1.0 : 0.45);
    }

    private java.util.function.Function<Color, Node> icon(java.util.function.Function<Color, javafx.scene.Group> vector) {
        return c -> panel.scaledIcon(vector.apply(c), ICON);
    }

    private static java.util.function.Function<Color, Node> glyph(String glyph) {
        return c -> {
            Label l = new Label(glyph);
            l.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, GLYPH));
            l.setTextFill(c);
            return l;
        };
    }

    /** A small framed text button — the mini-player's button look (QTraceMiniPlayerBar.style). */
    private static Button boxed(String text, String title) {
        Button b = new Button(text);
        b.setTooltip(tip(title));
        b.setStyle("-fx-background-color: " + QTracePanel.BG_SURFACE + "; -fx-text-fill: " + QTracePanel.TEXT_MAIN + ";"
            + "-fx-border-color: " + QTracePanel.BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4;"
            + "-fx-cursor: hand; -fx-padding: 2 7 2 7;");
        return b;
    }

    /** A button named by its panel caption (i18n key) — the name shows the moment it is hovered. */
    private Button button(java.util.function.Function<Color, Node> icon, String captionKey, Color hover) {
        return titled(icon, QTraceI18n.t(captionKey), hover);
    }

    private Button titled(java.util.function.Function<Color, Node> icon, String title, Color hover) {
        Button b = panel.iconOnlyButton(icon, title, hover);
        b.setTooltip(tip(title));
        return b;
    }

    /** Icons carry no caption here, so their name must not wait for the usual tooltip delay. */
    private static Tooltip tip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.ZERO);
        return t;
    }

    /** Stands in for the Upload button while a push runs — nothing to click, just what is going on. */
    private static Node uploadSpinner() {
        javafx.scene.control.ProgressIndicator spin = new javafx.scene.control.ProgressIndicator();
        spin.setPrefSize(ICON, ICON);
        spin.setMaxSize(ICON, ICON);
        spin.setMouseTransparent(true); // the box below takes the hover
        spin.setStyle("-fx-progress-color: " + QTracePanel.GROUP_WORKSPACE + ";");
        StackPane box = new StackPane(spin);
        box.setPadding(new Insets(3));
        Tooltip.install(box, tip("Upload in progress…"));
        return box;
    }

    /** A speech bubble with an exclamation mark — "tell us": bug or feature request. */
    private static Node reportIcon(Color c) {
        javafx.scene.shape.Path bubble = new javafx.scene.shape.Path(
            new javafx.scene.shape.MoveTo(3, 2),
            new javafx.scene.shape.LineTo(13, 2),
            new javafx.scene.shape.QuadCurveTo(15, 2, 15, 4),
            new javafx.scene.shape.LineTo(15, 9),
            new javafx.scene.shape.QuadCurveTo(15, 11, 13, 11),
            new javafx.scene.shape.LineTo(7.5, 11),
            new javafx.scene.shape.LineTo(4.5, 14),
            new javafx.scene.shape.LineTo(4.5, 11),
            new javafx.scene.shape.LineTo(3, 11),
            new javafx.scene.shape.QuadCurveTo(1, 11, 1, 9),
            new javafx.scene.shape.LineTo(1, 4),
            new javafx.scene.shape.QuadCurveTo(1, 2, 3, 2),
            new javafx.scene.shape.ClosePath());
        bubble.setStroke(c);
        bubble.setStrokeWidth(1.3);
        bubble.setFill(Color.TRANSPARENT);
        bubble.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        javafx.scene.shape.Line bar = new javafx.scene.shape.Line(8, 4.3, 8, 7);
        bar.setStroke(c);
        bar.setStrokeWidth(1.4);
        bar.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        return new javafx.scene.Group(bubble, bar, new Circle(8, 9, 0.8, c));
    }

    private static Region separator() {
        Region sep = new Region();
        sep.setPrefSize(ICON, 1);
        sep.setMinHeight(1);
        sep.setMaxSize(ICON, 1);
        sep.setStyle("-fx-background-color: " + QTracePanel.BORDER + ";");
        VBox.setMargin(sep, new Insets(3, 0, 3, 0));
        return sep;
    }

    private void startBlink() {
        captureBlink = new Timeline(
            new KeyFrame(Duration.ZERO,         new KeyValue(captureDot.opacityProperty(), 1.0)),
            new KeyFrame(Duration.millis(1200), new KeyValue(captureDot.opacityProperty(), 0.35)),
            new KeyFrame(Duration.millis(2400), new KeyValue(captureDot.opacityProperty(), 1.0))
        );
        captureBlink.setCycleCount(Timeline.INDEFINITE);
        captureBlink.play();
    }

    private void stopBlink() {
        if (captureBlink != null) { captureBlink.stop(); captureBlink = null; }
        if (captureDot != null) captureDot.setOpacity(1.0);
    }

    /** Puts the column at its remembered spot, inside the viewers whatever their size. */
    private void place() {
        column.relocate(
            MiniPanelPlacement.position(fx, layer.getWidth(), column.getWidth(), MARGIN),
            MiniPanelPlacement.position(fy, layer.getHeight(), column.getHeight(), MARGIN));
    }

    private void startDrag(MouseEvent e) {
        dragDx = e.getSceneX() - column.getLayoutX();
        dragDy = e.getSceneY() - column.getLayoutY();
    }

    private void drag(MouseEvent e) {
        fx = MiniPanelPlacement.fraction(e.getSceneX() - dragDx, layer.getWidth(), column.getWidth(), MARGIN, fx);
        fy = MiniPanelPlacement.fraction(e.getSceneY() - dragDy, layer.getHeight(), column.getHeight(), MARGIN, fy);
        place();
    }

    private void endDrag() {
        PREFS.putDouble(PREF_X, fx);
        PREFS.putDouble(PREF_Y, fy);
    }
}
