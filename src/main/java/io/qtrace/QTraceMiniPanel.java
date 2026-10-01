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
 * image by its ⠿ handle — position and folded state are remembered across sessions:
 *
 * <pre>
 *  ⠿  ●  [⚠]  Stamp │ Upload Replay │ Version(s) Report │ Dashboard Import Reset │ ⚙ ⤢ ▴
 * </pre>
 *
 * A view over a {@link QTracePanel} built but not shown — same state, same actions: ⤢ shows
 * that panel's window (counters, integrity alert, activity log).
 */
public final class QTraceMiniPanel {

    private static final double MARGIN = 8; // kept between the column and the viewers' edges
    private static final Preferences PREFS = Preferences.userNodeForPackage(QTraceMiniPanel.class);
    private static final String PREF_X = "miniPanelX", PREF_Y = "miniPanelY", PREF_COLLAPSED = "miniPanelCollapsed";

    private final QuPathGUI qupath;
    private final QTraceController controller;
    private final QTracePanel panel;
    private final VBox column;
    private final Pane layer; // full-size, click-through; the column sits in it
    private ViewerOverlay overlay;
    // Where the column sits, as fractions of the free space (1, 0.5 = right edge, vertically centred).
    private double fx = PREFS.getDouble(PREF_X, 1.0), fy = PREFS.getDouble(PREF_Y, 0.5);
    private double dragDx, dragDy;
    private boolean collapsed = PREFS.getBoolean(PREF_COLLAPSED, false);

    private Circle captureDot;
    private Timeline captureBlink;
    private Label integrityBadge;
    private Button stampBtn, uploadBtn, resetBtn;

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
        panel.addStateListener(this::refresh);
    }

    public boolean isDocked() { return overlay != null; }

    /** The column itself — for the screenshot harness. */
    public VBox column() { return column; }

    /** Unfolds the column — "Panel" chosen again while it is already docked. */
    public void expand() {
        if (collapsed) setCollapsed(false);
    }

    /** Rebuilds the buttons — the entitlement decides which groups exist. FX thread. */
    public void rebuild() {
        stopBlink();
        uploadBtn = null;
        List<Node> nodes = new ArrayList<>();

        Label handle = new Label("⠿");
        handle.setFont(Font.font("System", 16));
        handle.setTextFill(Color.web(QTracePanel.TEXT_MUTED));
        handle.setCursor(Cursor.MOVE);
        handle.setTooltip(new Tooltip("Drag to move the panel"));
        handle.setOnMousePressed(this::startDrag);
        handle.setOnMouseDragged(this::drag);
        handle.setOnMouseReleased(e -> endDrag());
        nodes.add(handle);

        // Passive capture status — red (blinking) while recording, grey when paused.
        captureDot = new Circle(4.5, Color.web(QTracePanel.TEXT_MUTED));
        StackPane dotBox = new StackPane(captureDot);
        dotBox.setPadding(new Insets(3, 0, 3, 0));
        nodes.add(dotBox);

        // Integrity alert for the open image — hidden unless its stamp no longer holds.
        integrityBadge = new Label();
        integrityBadge.setFont(Font.font("System", 14));
        integrityBadge.setCursor(Cursor.HAND);
        integrityBadge.setOnMouseClicked(e -> {
            Runnable why = panel.integrityOnWhy();
            if (why != null) why.run();
        });
        nodes.add(integrityBadge);

        if (!collapsed) {
            Color teal = Color.web(QTracePanel.CTA_TEAL);
            stampBtn = panel.iconOnlyButton(c -> panel.scaledIcon(panel.iconStamp(teal), 18),
                QTraceI18n.t("btn.stamp.tooltip"), teal);
            stampBtn.setOnAction(e -> controller.recordTrace());
            nodes.add(stampBtn);

            // Workspace & Analyse — same rule as the panel's toolbar: only when licensed & active.
            if (QTracePluginManager.isEntitled()) {
                Color workspace = Color.web(QTracePanel.GROUP_WORKSPACE);
                nodes.add(separator());
                uploadBtn = button(panel.iconFactory(panel::iconUpload), "btn.upload.tooltip", workspace);
                uploadBtn.setOnAction(e -> controller.pushToWorkspace());
                Button replayBtn = button(panel.iconFactory(panel::iconReplay), "btn.replay.tooltip", workspace);
                replayBtn.setOnAction(e -> controller.openReplayDialog());
                nodes.add(uploadBtn);
                nodes.add(replayBtn);
                nodes.add(separator());
                Button versionsBtn = button(panel.iconFactory(panel::iconVersions), "btn.versions.tooltip", workspace);
                versionsBtn.setOnAction(e -> controller.showCommitGraph());
                Button reportBtn = button(panel.iconFactory(panel::iconReport), "btn.report.tooltip", workspace);
                reportBtn.setOnAction(e -> controller.generateActivityReport());
                nodes.add(versionsBtn);
                nodes.add(reportBtn);
            }

            Color tools = Color.web(QTracePanel.GROUP_TOOLS);
            nodes.add(separator());
            Button dashboardBtn = button(panel.iconFactory(panel::iconDashboard), "btn.dashboard.tooltip", tools);
            dashboardBtn.setOnAction(e -> controller.showDashboard());
            Button importBtn = button(panel.iconFactory(panel::iconImport), "btn.import.tooltip", tools);
            importBtn.setOnAction(e -> controller.startBatchExport());
            resetBtn = button(panel.iconFactory(panel::iconReset), "btn.reset.tooltip", Color.web(QTracePanel.RED));
            resetBtn.setOnAction(e -> panel.confirmReset());
            nodes.add(dashboardBtn);
            nodes.add(importBtn);
            nodes.add(resetBtn);

            Color admin = Color.web(QTracePanel.GROUP_ADMIN);
            nodes.add(separator());
            Button settingsBtn = button(panel.glyphIcon("⚙"), "btn.settings.tooltip", admin);
            settingsBtn.setOnAction(e -> QTraceSettingsDialog.show(qupath));
            Button expandBtn = panel.iconOnlyButton(panel.glyphIcon("⤢"),
                "Open the full panel (counters, activity log)", admin);
            expandBtn.setOnAction(e -> panel.show());
            nodes.add(settingsBtn);
            nodes.add(expandBtn);
        } else {
            stampBtn = null;
            resetBtn = null;
        }

        Button collapseBtn = panel.iconOnlyButton(panel.glyphIcon(collapsed ? "▾" : "▴"),
            collapsed ? "Show the panel's buttons" : "Fold the panel", Color.web(QTracePanel.GROUP_ADMIN));
        collapseBtn.setOnAction(e -> setCollapsed(!collapsed));
        nodes.add(collapseBtn);

        column.getChildren().setAll(nodes);
        refresh();
    }

    /** Re-renders from the panel's state — on every state change. FX thread. */
    private void refresh() {
        boolean recording = panel.isRecordingActive();
        captureDot.setFill(Color.web(recording ? QTracePanel.RED : QTracePanel.TEXT_MUTED));
        Tooltip.install(captureDot.getParent(), new Tooltip(recording ? "Recording" : "Paused"));
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
            integrityBadge.setTooltip(new Tooltip(QTracePanel.integrityMessage(state) + " — click for details"));
        }

        enable(stampBtn, panel.isRecordReady());
        enable(uploadBtn, panel.isPushEnabled());
        enable(resetBtn, controller.hasActiveImage());
    }

    private static void enable(Button b, boolean enabled) {
        if (b == null) return;
        b.setDisable(!enabled);
        b.setOpacity(enabled ? 1.0 : 0.45);
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        PREFS.putBoolean(PREF_COLLAPSED, c);
        rebuild();
    }

    private Button button(java.util.function.Function<Color, Node> icon, String tooltipKey, Color hover) {
        return panel.iconOnlyButton(icon, QTraceI18n.t(tooltipKey), hover);
    }

    private static Region separator() {
        Region sep = new Region();
        sep.setPrefSize(18, 1);
        sep.setMinHeight(1);
        sep.setMaxSize(18, 1);
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
