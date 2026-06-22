package io.astraebio.qtrace;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;

public class QTracePanel {

    // Catppuccin Mocha palette — chosen to complement QuPath's dark theme
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";
    private static final String PEACH      = "#fab387";
    private static final String RED        = "#f38ba8";

    private final Stage stage;
    private final QTraceController controller;

    // Status widgets
    private Circle   statusDot;
    private Label    statusLabel;
    private Label    imageNameLabel;
    private Label    stepCountLabel;
    private Label    preExistingLabel;
    private Label    corrCountLabel;

    // Record button (header)
    private Button   btnRecord;
    // Cloud push button (Enterprise only)
    private Button   btnPush;

    // Log
    private TextArea logArea;
    private Label    progressLabel;
    private Timeline progressTimeline;

    public QTracePanel(QuPathGUI qupath, QTraceController controller) {
        this.controller = controller;
        this.stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle(QTraceController.getEditionLabel());
        stage.setResizable(true);
        stage.setMinWidth(280);
        stage.setMinHeight(340);
        stage.setWidth(320);
        stage.setHeight(460);
        Image logo = loadLogo();
        if (logo != null) stage.getIcons().add(logo);
        stage.setScene(new Scene(buildRoot()));
        stage.setOnCloseRequest(e ->
            log("Panel closed — recording state preserved until QuPath restarts."));
    }

    // ── Root layout ──────────────────────────────────────────────────────────

    private VBox buildRoot() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(16, 16, 4, 16));
        root.setStyle("-fx-background-color: " + BG_BASE + ";");

        VBox logSection = buildLogSection();
        VBox.setVgrow(logSection, Priority.ALWAYS);

        root.getChildren().addAll(
            buildHeader(),
            separator(),
            buildStatusSection(),
            separator(),
            logSection,
            buildFooter()
        );
        return root;
    }

    // ── Header ───────────────────────────────────────────────────────────────

    private HBox buildHeader() {
        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);

        ImageView logoView = logoView(28);

        String edition = QTracePluginManager.hasEnterprise()
            ? "Enterprise" + QTraceController.entitlementSuffix()
            : "Core";
        Text title = new Text("qTrace " + edition);
        title.setFont(Font.font("System", FontWeight.BOLD, 18));
        title.setFill(Color.web(TEXT_MAIN));

        Text sub = new Text("Workflow Provenance — v" + QTraceController.getDisplayVersion());
        sub.setFont(Font.font("System", 11));
        sub.setFill(Color.web(TEXT_MUTED));

        // Enterprise header line: green "✓ Name" when licensed & active,
        // amber "⚠ License expired — renew" when the JAR is present but not entitled.
        String licenseeLine = null;
        boolean licenseWarn = false;
        QTracePlugin entitled = QTracePluginManager.getEntitled();
        if (entitled != null) {
            LicenseInfo li = entitled.getActiveLicenseInfo();
            if (li != null) licenseeLine = "✓ " + li.name();
        } else if (QTracePluginManager.hasEnterprise()) {
            licenseeLine = "⚠ " + QTraceI18n.t("license.inactive.header");
            licenseWarn = true;
        }
        boolean licenseError = QTraceController.entitlementIsError(); // invalid signature → red
        Text licenseText = licenseeLine != null ? new Text(licenseeLine) : null;
        if (licenseText != null) {
            licenseText.setFont(Font.font("System", FontWeight.BOLD, 11));
            licenseText.setFill(Color.web(licenseError ? RED : licenseWarn ? PEACH : GREEN));
        }

        VBox titleBlock = licenseText != null
            ? new VBox(1, title, sub, licenseText)
            : new VBox(1, title, sub);
        titleBlock.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ◉ Record your Trace — primary action button
        btnRecord = new Button("◉");
        btnRecord.setFont(Font.font("System", FontWeight.BOLD, 16));
        btnRecord.setTextFill(Color.web(TEXT_MUTED));
        Tooltip recordTip = new Tooltip(QTraceI18n.t("btn.record.tooltip"));
        recordTip.setWrapText(true);
        recordTip.setMaxWidth(260);
        btnRecord.setTooltip(recordTip);
        btnRecord.setDisable(true);
        btnRecord.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 0 4 0 4;"
        );
        btnRecord.setOnMouseEntered(e -> {
            if (!btnRecord.isDisabled()) btnRecord.setTextFill(Color.web("#ff6680"));
        });
        btnRecord.setOnMouseExited(e -> {
            if (!btnRecord.isDisabled()) btnRecord.setTextFill(Color.web(RED));
        });
        btnRecord.setOnAction(e -> controller.recordTrace());

        Button dashboard = new Button("⊞");
        dashboard.setFont(Font.font("System", 14));
        dashboard.setTextFill(Color.web(TEXT_MUTED));
        dashboard.setTooltip(new Tooltip("Open Dashboard — inspect any .qtrace file"));
        dashboard.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 0 4 0 4;"
        );
        dashboard.setOnMouseEntered(e -> dashboard.setTextFill(Color.web(BLUE)));
        dashboard.setOnMouseExited(e  -> dashboard.setTextFill(Color.web(TEXT_MUTED)));
        dashboard.setOnAction(e -> controller.showDashboard());

        Button batchBtn = new Button("⇩");
        batchBtn.setFont(Font.font("System", 14));
        batchBtn.setTextFill(Color.web(TEXT_MUTED));
        batchBtn.setTooltip(new Tooltip("Batch Import — generate .qtrace for all project's images"));
        batchBtn.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 0 4 0 4;"
        );
        batchBtn.setOnMouseEntered(e -> batchBtn.setTextFill(Color.web(GREEN)));
        batchBtn.setOnMouseExited(e  -> batchBtn.setTextFill(Color.web(TEXT_MUTED)));
        batchBtn.setOnAction(e -> controller.startBatchExport());

        Button reset = new Button("↺");
        reset.setFont(Font.font("System", 14));
        reset.setTextFill(Color.web(TEXT_MUTED));
        reset.setTooltip(new Tooltip("Reset — clear captured history, restart tracking from now"));
        reset.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 0 4 0 4;"
        );
        reset.setOnMouseEntered(e -> reset.setTextFill(Color.web(RED)));
        reset.setOnMouseExited(e  -> reset.setTextFill(Color.web(TEXT_MUTED)));
        reset.setOnAction(e -> controller.resetCapture());

        Button gear = new Button("⚙");
        gear.setFont(Font.font("System", 13));
        gear.setTextFill(Color.web(TEXT_MUTED));
        gear.setTooltip(new Tooltip("Export path settings"));
        gear.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 0 4 0 4;"
        );
        gear.setOnMouseEntered(e -> gear.setTextFill(Color.web(TEXT_MAIN)));
        gear.setOnMouseExited(e  -> gear.setTextFill(Color.web(TEXT_MUTED)));
        gear.setOnAction(e -> QTraceSettingsDialog.show(stage));

        // Cloud workspace push + Replay + Graph — Enterprise, only when licensed & active.
        // When the license is inactive the panel degrades to the Core button set.
        if (QTracePluginManager.isEntitled()) {
            btnPush = new Button("☁");
            btnPush.setFont(Font.font("System", 14));
            btnPush.setTextFill(Color.web(TEXT_MUTED));
            btnPush.setTooltip(new Tooltip(
                "Push to workspace — upload this certificate to your qtrace.ca workspace for Bitcoin anchoring"));
            btnPush.setStyle(
                "-fx-background-color: transparent;"
              + "-fx-cursor: hand;"
              + "-fx-padding: 0 4 0 4;"
            );
            btnPush.setDisable(true);
            btnPush.setOnMouseEntered(e -> { if (!btnPush.isDisabled()) btnPush.setTextFill(Color.web(BLUE)); });
            btnPush.setOnMouseExited(e  -> { if (!btnPush.isDisabled()) btnPush.setTextFill(Color.web(TEXT_MUTED)); });
            btnPush.setOnAction(e -> controller.pushToWorkspace());

            Button btnReplay = new Button("▶");
            btnReplay.setFont(Font.font("System", FontWeight.BOLD, 13));
            btnReplay.setTextFill(Color.web(GREEN));
            btnReplay.setTooltip(new Tooltip("Replay from workspace — load a qtc_ bundle into the Script Editor"));
            btnReplay.setStyle(
                "-fx-background-color: transparent;"
              + "-fx-cursor: hand;"
              + "-fx-padding: 0 4 0 4;"
            );
            btnReplay.setOnMouseEntered(e -> btnReplay.setTextFill(Color.web(TEXT_MAIN)));
            btnReplay.setOnMouseExited(e  -> btnReplay.setTextFill(Color.web(GREEN)));
            btnReplay.setOnAction(e -> controller.openReplayDialog());

            // ⑃ Commit graph — visualize the .qtrace version history (contributors per commit)
            Button btnGraph = new Button("⑃");
            btnGraph.setFont(Font.font("System", FontWeight.BOLD, 14));
            btnGraph.setTextFill(Color.web(TEXT_MUTED));
            btnGraph.setTooltip(new Tooltip(QTraceI18n.t("btn.graph.tooltip")));
            btnGraph.setStyle(
                "-fx-background-color: transparent;"
              + "-fx-cursor: hand;"
              + "-fx-padding: 0 4 0 4;"
            );
            btnGraph.setOnMouseEntered(e -> btnGraph.setTextFill(Color.web(BLUE)));
            btnGraph.setOnMouseExited(e  -> btnGraph.setTextFill(Color.web(TEXT_MUTED)));
            btnGraph.setOnAction(e -> controller.showCommitGraph());

            header.getChildren().addAll(logoView, titleBlock, spacer, btnRecord, btnPush, btnReplay, btnGraph, dashboard, batchBtn, reset, gear);
        } else {
            header.getChildren().addAll(logoView, titleBlock, spacer, btnRecord, dashboard, batchBtn, reset, gear);
        }
        return header;
    }

    // ── Status section ───────────────────────────────────────────────────────

    private VBox buildStatusSection() {
        VBox section = new VBox(8);

        // Row 1: recording indicator
        HBox statusRow = new HBox(8);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusDot   = new Circle(5, Color.web(TEXT_MUTED));
        statusLabel = styledLabel("Idle — awaiting recording", TEXT_SUB, FontWeight.BOLD, 12);
        statusRow.getChildren().addAll(statusDot, statusLabel);

        // Row 2: current image
        HBox imageRow = new HBox(6);
        imageRow.setAlignment(Pos.CENTER_LEFT);
        imageRow.getChildren().addAll(
            styledLabel("Image :", TEXT_MUTED, FontWeight.NORMAL, 11),
            imageNameLabel = styledLabel(controller.getCurrentImageName(), TEXT_MAIN, FontWeight.NORMAL, 11)
        );

        // Row 3: counters
        HBox countersRow = new HBox(20);
        countersRow.setAlignment(Pos.CENTER_LEFT);

        stepCountLabel   = bigNumber("0");
        preExistingLabel = styledLabel("", TEXT_MUTED, FontWeight.NORMAL, 9);
        Label stepTitle  = styledLabel("Steps captured", TEXT_MUTED, FontWeight.NORMAL, 10);
        stepCountLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
        stepCountLabel.setTextFill(Color.web(BLUE));
        VBox stepsBox = new VBox(1, stepTitle, stepCountLabel, preExistingLabel);
        stepsBox.setAlignment(Pos.CENTER_LEFT);

        countersRow.getChildren().addAll(
            stepsBox,
            buildCounter("Manual corrections", PEACH, corrCountLabel = bigNumber("0"))
        );

        section.getChildren().addAll(statusRow, imageRow, countersRow);
        return section;
    }

    private VBox buildCounter(String title, String color, Label numberLabel) {
        Label titleLbl = styledLabel(title, TEXT_MUTED, FontWeight.NORMAL, 10);
        numberLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
        numberLabel.setTextFill(Color.web(color));
        VBox box = new VBox(2, titleLbl, numberLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Label bigNumber(String value) {
        Label lbl = new Label(value);
        lbl.setFont(Font.font("System", FontWeight.BOLD, 22));
        return lbl;
    }

    // ── Log section ──────────────────────────────────────────────────────────

    private VBox buildLogSection() {
        VBox section = new VBox(4);

        Label title = styledLabel("Activity log", TEXT_MUTED, FontWeight.NORMAL, 10);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(90);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        logArea.setStyle(
            "-fx-background-color: " + BG_SURFACE + ";"
          + "-fx-text-fill: " + TEXT_SUB + ";"
          + "-fx-font-size: 10;"
          + "-fx-font-family: monospace;"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-control-inner-background: " + BG_SURFACE + ";"
        );
        logArea.appendText("[QTrace " + QTraceController.VERSION + "] Initialized.\n");

        progressLabel = new Label();
        progressLabel.setVisible(false);
        progressLabel.setStyle(
            "-fx-text-fill: " + BLUE + ";"
          + "-fx-font-size: 10;"
          + "-fx-font-family: monospace;"
        );

        section.getChildren().addAll(title, logArea, progressLabel);
        return section;
    }

    // ── Footer / resize grip ─────────────────────────────────────────────────

    private HBox buildFooter() {
        HBox footer = new HBox();
        footer.setAlignment(Pos.BOTTOM_LEFT);
        footer.setPadding(new Insets(2, 0, 0, 0));

        Region grip = new Region();
        grip.setPrefSize(14, 14);
        grip.setMinSize(14, 14);
        grip.setMaxSize(14, 14);
        grip.setCursor(Cursor.SW_RESIZE);
        grip.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-border-color: " + TEXT_MUTED + ";"
          + "-fx-border-width: 0 0 2 2;"
        );

        // drag state: [mouseX, mouseY, stageX, stageW, stageH]
        double[] drag = {0, 0, 0, 0, 0};

        grip.setOnMousePressed(e -> {
            drag[0] = e.getScreenX();
            drag[1] = e.getScreenY();
            drag[2] = stage.getX();
            drag[3] = stage.getWidth();
            drag[4] = stage.getHeight();
        });

        grip.setOnMouseDragged(e -> {
            double dx = e.getScreenX() - drag[0];
            double dy = e.getScreenY() - drag[1];

            // Left edge: drag left → expand, drag right → shrink
            double newWidth = drag[3] - dx;
            double newX     = drag[2] + dx;
            if (newWidth < stage.getMinWidth()) {
                newWidth = stage.getMinWidth();
                newX     = drag[2] + drag[3] - stage.getMinWidth();
            }
            stage.setX(newX);
            stage.setWidth(newWidth);

            // Bottom edge: drag down → taller, drag up → shorter
            double newHeight = drag[4] + dy;
            if (newHeight >= stage.getMinHeight()) {
                stage.setHeight(newHeight);
            }
        });

        footer.getChildren().add(grip);
        return footer;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Separator separator() {
        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: " + BORDER + ";");
        return sep;
    }

    private Label styledLabel(String text, String color, FontWeight weight, int size) {
        Label lbl = new Label(text);
        lbl.setTextFill(Color.web(color));
        lbl.setFont(Font.font("System", weight, size));
        return lbl;
    }

    // ── Logo helpers ─────────────────────────────────────────────────────────

    static Image loadLogo() {
        try (var is = QTracePanel.class.getResourceAsStream("/io/astraebio/qtrace/logo.png")) {
            if (is != null) return new Image(is);
        } catch (Exception ignored) {}
        return null;
    }

    static ImageView logoView(int size) {
        Image img = loadLogo();
        if (img == null) return new ImageView();
        ImageView iv = new ImageView(img);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);
        return iv;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void show() {
        stage.show();
        stage.toFront();
        refreshStatus();
        log("Panel opened — image: " + controller.getCurrentImageName());
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    /** Rebuilds the scene to reflect a changed entitlement (e.g. license downgrade). */
    public void refresh() {
        Platform.runLater(() -> {
            stage.setTitle(QTraceController.getEditionLabel());
            stage.setScene(new Scene(buildRoot()));
            refreshStatus();
        });
    }

    public void log(String message) {
        Platform.runLater(() -> {
            logArea.appendText(message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    /** Starts an animated "uploading…" progress bar in the log panel. Call from any thread. */
    public void startPushProgress() {
        Platform.runLater(() -> {
            String[] frames = {
                "☁ ·          ",
                "☁ ··         ",
                "☁ ···        ",
                "☁  ···       ",
                "☁   ···      ",
                "☁    ···     ",
                "☁     ···    ",
                "☁      ···   ",
                "☁       ···  ",
                "☁        ··· ",
                "☁         ···",
                "☁        ··· ",
                "☁       ···  ",
                "☁      ···   ",
                "☁     ···    ",
                "☁    ···     ",
                "☁   ···      ",
                "☁  ···       ",
                "☁ ···        ",
                "☁ ··         ",
            };
            final int[] idx = {0};
            progressLabel.setText(frames[0]);
            progressLabel.setVisible(true);
            progressTimeline = new Timeline(new KeyFrame(Duration.millis(80), e -> {
                idx[0] = (idx[0] + 1) % frames.length;
                progressLabel.setText(frames[idx[0]]);
            }));
            progressTimeline.setCycleCount(Timeline.INDEFINITE);
            progressTimeline.play();
        });
    }

    /** Stops the push progress animation. Call from any thread. */
    public void stopPushProgress() {
        Platform.runLater(() -> {
            if (progressTimeline != null) {
                progressTimeline.stop();
                progressTimeline = null;
            }
            progressLabel.setVisible(false);
        });
    }

    /** Enable/disable the ☁ push button (Enterprise only — no-op in Core). */
    public void setPushEnabled(boolean enabled) {
        if (btnPush == null) return;
        Platform.runLater(() -> {
            btnPush.setDisable(!enabled);
            btnPush.setTextFill(Color.web(enabled ? BLUE : TEXT_MUTED));
        });
    }

    /** Called by ActionLogger on each captured step. */
    public void updateStepCount(int steps, int preExisting, int manualCorrections) {
        Platform.runLater(() -> {
            stepCountLabel.setText(String.valueOf(steps));
            corrCountLabel.setText(String.valueOf(manualCorrections));
            if (preExisting > 0 && preExisting < steps)
                preExistingLabel.setText(preExisting + " pre-existing");
            else if (preExisting > 0 && preExisting == steps)
                preExistingLabel.setText("all pre-existing");
            else
                preExistingLabel.setText("");
        });
    }

    /** Toggle recording indicator dot. */
    public void setRecordingActive(boolean active) {
        Platform.runLater(() -> {
            if (active) {
                statusDot.setFill(Color.web(GREEN));
                statusLabel.setText("Recording");
                statusLabel.setTextFill(Color.web(GREEN));
            } else {
                statusDot.setFill(Color.web(TEXT_MUTED));
                statusLabel.setText("Idle — awaiting recording");
                statusLabel.setTextFill(Color.web(TEXT_SUB));
            }
        });
    }

    /** Enable the ◉ Record button once steps are captured. */
    public void setRecordReady(boolean ready) {
        Platform.runLater(() -> {
            btnRecord.setDisable(!ready);
            btnRecord.setTextFill(Color.web(ready ? RED : TEXT_MUTED));
        });
    }

    /** Update status indicator after validation. */
    public void setValidated(boolean validated, String validatorName) {
        Platform.runLater(() -> {
            if (validated) {
                statusDot.setFill(Color.web(PEACH));
                statusLabel.setText("Validated by " + validatorName);
                statusLabel.setTextFill(Color.web(PEACH));
            } else {
                statusDot.setFill(Color.web(TEXT_MUTED));
                statusLabel.setText("Idle — awaiting recording");
                statusLabel.setTextFill(Color.web(TEXT_SUB));
            }
        });
    }

    /** Refresh image name label (called after image change). */
    public void refreshStatus() {
        Platform.runLater(() -> imageNameLabel.setText(controller.getCurrentImageName()));
    }
}
