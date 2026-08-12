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

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.*;
import javafx.scene.transform.Scale;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.util.function.Function;
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

    // Toolbar redesign — CTA + functional group accents
    private static final String BG_ELEVATED = "#26263a";
    private static final String TEXT_FAINT  = "#4a4a5e";
    private static final String CTA_TEAL       = "#2dd4bf";
    private static final String CTA_TEAL_SOFT  = "rgba(45,212,191,0.14)";
    private static final String CTA_TEAL_BORDER = "rgba(45,212,191,0.35)";
    private static final String GROUP_WORKSPACE = "#8B5CF6";
    private static final String GROUP_TOOLS     = "#10B981";
    private static final String GROUP_ADMIN     = "#9CA3AF";
    private static final String GOLD            = "#d9b34d";

    private final Stage stage;
    private final QTraceController controller;

    // Status widgets
    private Circle   statusDot;
    private Label    statusLabel;
    private Label    imageNameLabel;
    private Label    stepCountLabel;
    private Label    preExistingLabel;
    private Label    corrCountLabel;

    // Capture status — top-right, on the title's line (passive, non-clickable)
    private Circle   captureDot;
    private Label    captureLabel;
    private Timeline captureBlink;

    // Record button (header) — CTA "Stamp"
    private Button   btnRecord;
    // Cloud push button (Compliance only) — "Upload"
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
        stage.setMinHeight(340);
        stage.setHeight(460);
        if (QTracePluginManager.isEntitled()) {
            stage.setMinWidth(560);
            stage.setWidth(560);
        } else {
            // Core toolbar now carries Dashboard + Import + Reset — widened from 340/360 so
            // the extra icon button doesn't get squeezed or wrap onto a second line.
            stage.setMinWidth(380);
            stage.setWidth(400);
        }
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

    private VBox buildHeader() {
        VBox header = new VBox(4);

        // ── Line 1: logo + title + passive capture status (top-right, title's line) ──
        HBox topLine = new HBox(10);
        topLine.setAlignment(Pos.CENTER_LEFT);

        ImageView logoView = logoView(26);

        String edition = QTracePluginManager.hasCompliance()
            ? "Compliance" + QTraceController.entitlementSuffix()
            : "Core";
        Text title = new Text("qTrace " + edition);
        title.setFont(Font.font("System", FontWeight.BOLD, 18));
        title.setFill(Color.web(TEXT_MAIN));

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        HBox captureStatus = buildCaptureStatus();

        Button settingsBtn = iconOnlyButton(glyphIcon("⚙"), QTraceI18n.t("btn.settings.tooltip"), Color.web(GROUP_ADMIN));
        settingsBtn.setOnAction(e -> QTraceSettingsDialog.show(stage));

        topLine.getChildren().addAll(logoView, title, topSpacer, captureStatus, settingsBtn);

        // ── Line 2: subtitle + license badge, indented under the title ──
        Text sub = new Text("Workflow Provenance — v" + QTraceController.getDisplayVersion());
        sub.setFont(Font.font("System", 11));
        sub.setFill(Color.web(TEXT_MUTED));

        VBox metaBlock = new VBox(1, sub, buildLicenseBadge());
        metaBlock.setPadding(new Insets(0, 0, 0, 36));

        header.getChildren().addAll(topLine, metaBlock, buildToolbarRow());
        return header;
    }

    // ── Capture status (passive — Recording / Paused) ───────────────────────────

    private HBox buildCaptureStatus() {
        captureDot = new Circle(3.2, Color.web(TEXT_MUTED));
        captureLabel = styledLabel("Paused", TEXT_MUTED, FontWeight.BOLD, 10);
        HBox box = new HBox(5, captureDot, captureLabel);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private void startCaptureBlink() {
        if (captureBlink != null) captureBlink.stop();
        captureBlink = new Timeline(
            new KeyFrame(Duration.ZERO,           new KeyValue(captureDot.opacityProperty(), 1.0)),
            new KeyFrame(Duration.millis(1200),   new KeyValue(captureDot.opacityProperty(), 0.35)),
            new KeyFrame(Duration.millis(2400),   new KeyValue(captureDot.opacityProperty(), 1.0))
        );
        captureBlink.setCycleCount(Timeline.INDEFINITE);
        captureBlink.play();
    }

    private void stopCaptureBlink() {
        if (captureBlink != null) { captureBlink.stop(); captureBlink = null; }
        if (captureDot != null) captureDot.setOpacity(1.0);
    }

    // ── License badge (medal icon + discreet text) ──────────────────────────────

    private HBox buildLicenseBadge() {
        QTracePlugin entitled = QTracePluginManager.getEntitled();
        String text;
        String iconColor;
        boolean urgent = false; // keep the text itself colored for warning/error states

        if (entitled != null && entitled.getActiveLicenseInfo() != null) {
            LicenseInfo li = entitled.getActiveLicenseInfo();
            text = "Certified for " + li.name() + " — " + li.validatorKeyShort()
                 + "  ·  until " + li.expiresAtFormatted().replace("-", "/");
            iconColor = GOLD;
        } else if (QTracePluginManager.hasCompliance()) {
            text = QTraceI18n.t("license.inactive.header");
            iconColor = QTraceController.entitlementIsError() ? RED : PEACH;
            urgent = true;
        } else {
            text = "Core edition";
            iconColor = TEXT_MUTED;
        }

        Group icon = scaledIcon(iconMedal(Color.web(iconColor)), 11);
        Label label = styledLabel(text, urgent ? iconColor : TEXT_MUTED, FontWeight.NORMAL, 13);

        HBox badge = new HBox(4, icon, label);
        badge.setAlignment(Pos.CENTER_LEFT);
        return badge;
    }

    // ── Toolbar row (single line, CTA + functional groups) ──────────────────────

    private HBox buildToolbarRow() {
        HBox row = new HBox(9);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 0, 0, 0));

        // ⚡ Stamp — CTA. Validates & stamps the current trace (capture itself is passive).
        btnRecord = new Button(QTraceI18n.t("btn.stamp.caption"));
        btnRecord.setGraphic(scaledIcon(iconStamp(Color.web(CTA_TEAL)), 17));
        btnRecord.setContentDisplay(ContentDisplay.LEFT);
        btnRecord.setGraphicTextGap(7);
        btnRecord.setFont(Font.font("System", FontWeight.BOLD, 13));
        btnRecord.setTextFill(Color.web(CTA_TEAL));
        Tooltip recordTip = new Tooltip(QTraceI18n.t("btn.stamp.tooltip"));
        recordTip.setWrapText(true);
        recordTip.setMaxWidth(260);
        btnRecord.setTooltip(recordTip);
        btnRecord.setDisable(true);
        btnRecord.setOpacity(0.45);
        btnRecord.setStyle(
            "-fx-background-color: " + CTA_TEAL_SOFT + ";"
          + "-fx-background-radius: 7;"
          + "-fx-border-color: " + CTA_TEAL_BORDER + ";"
          + "-fx-border-radius: 7;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 6 12 6 10;"
        );
        btnRecord.setOnAction(e -> controller.recordTrace());
        row.getChildren().add(btnRecord);

        // Workspace & Analyse — Compliance only, when licensed & active.
        // When the license is inactive the panel degrades to the Core button set.
        if (QTracePluginManager.isEntitled()) {
            row.getChildren().add(vseparator());

            btnPush = iconButton(iconFactory(this::iconUpload), QTraceI18n.t("btn.upload.caption"),
                QTraceI18n.t("btn.upload.tooltip"), Color.web(GROUP_WORKSPACE));
            btnPush.setDisable(true);
            btnPush.setOpacity(0.45);
            btnPush.setOnAction(e -> controller.pushToWorkspace());

            Button btnReplay = iconButton(iconFactory(this::iconReplay), QTraceI18n.t("btn.replay.caption"),
                QTraceI18n.t("btn.replay.tooltip"), Color.web(GROUP_WORKSPACE));
            btnReplay.setOnAction(e -> controller.openReplayDialog());

            row.getChildren().addAll(btnPush, btnReplay);
            row.getChildren().add(vseparator());

            Button btnGraph = iconButton(iconFactory(this::iconVersions), QTraceI18n.t("btn.versions.caption"),
                QTraceI18n.t("btn.versions.tooltip"), Color.web(GROUP_WORKSPACE));
            btnGraph.setOnAction(e -> controller.showCommitGraph());

            Button btnReport = iconButton(iconFactory(this::iconReport), QTraceI18n.t("btn.report.caption"),
                QTraceI18n.t("btn.report.tooltip"), Color.web(GROUP_WORKSPACE));
            btnReport.setOnAction(e -> controller.generateActivityReport());

            row.getChildren().addAll(btnGraph, btnReport);
        }

        // Consultation & Traitement — always available, Core + Compliance.
        row.getChildren().add(vseparator());
        Button dashboardBtn = iconButton(iconFactory(this::iconDashboard), QTraceI18n.t("btn.dashboard.caption"),
            QTraceI18n.t("btn.dashboard.tooltip"), Color.web(GROUP_TOOLS));
        Button importBtn = iconButton(iconFactory(this::iconImport), QTraceI18n.t("btn.import.caption"),
            QTraceI18n.t("btn.import.tooltip"), Color.web(GROUP_TOOLS));
        Button resetBtn = iconButton(iconFactory(this::iconReset), QTraceI18n.t("btn.reset.caption"),
            QTraceI18n.t("btn.reset.tooltip"), Color.web(RED));
        dashboardBtn.setOnAction(e -> controller.showDashboard());
        importBtn.setOnAction(e -> controller.startBatchExport());
        resetBtn.setOnAction(e -> confirmReset());
        row.getChildren().addAll(dashboardBtn, importBtn, resetBtn);

        return row;
    }

    /**
     * Reset is destructive (clears the whole capture, see ActionLogger#resetCapture) — always
     * confirm before calling it, same as the "Unstamped image" guard elsewhere in this app.
     */
    private void confirmReset() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(controller.getQuPath().getStage());
        alert.setTitle("qTrace — Reset");
        alert.setHeaderText("Reset will erase all of your traces.");
        alert.setContentText("Are you sure you want to continue?");
        ButtonType btnReset  = new ButtonType("Reset", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(btnCancel, btnReset);

        alert.showAndWait().ifPresent(bt -> {
            if (bt == btnReset) controller.resetCapture();
        });
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

        Region imageRowSpacer = new Region();
        HBox.setHgrow(imageRowSpacer, Priority.ALWAYS);
        Button btnOpenQtrace = new Button(QTraceI18n.t("btn.openqtrace"));
        btnOpenQtrace.setStyle(
            "-fx-background-color:transparent;-fx-text-fill:" + BLUE + ";"
          + "-fx-cursor:hand;-fx-font-size:10;-fx-padding:1 6 1 6;"
          + "-fx-border-color:" + BLUE + ";-fx-border-radius:4;-fx-background-radius:4;");
        btnOpenQtrace.setTooltip(new Tooltip(QTraceI18n.t("btn.openqtrace.tooltip")));
        btnOpenQtrace.setOnMouseEntered(e -> btnOpenQtrace.setStyle(
            "-fx-background-color:#20304a;-fx-text-fill:" + BLUE + ";"
          + "-fx-cursor:hand;-fx-font-size:10;-fx-padding:1 6 1 6;"
          + "-fx-border-color:" + BLUE + ";-fx-border-radius:4;-fx-background-radius:4;"));
        btnOpenQtrace.setOnMouseExited(e -> btnOpenQtrace.setStyle(
            "-fx-background-color:transparent;-fx-text-fill:" + BLUE + ";"
          + "-fx-cursor:hand;-fx-font-size:10;-fx-padding:1 6 1 6;"
          + "-fx-border-color:" + BLUE + ";-fx-border-radius:4;-fx-background-radius:4;"));
        btnOpenQtrace.setOnAction(e -> controller.openCurrentQtraceFile());
        imageRow.getChildren().addAll(imageRowSpacer, btnOpenQtrace);

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

    // ── Toolbar button factory (icon on top, caption below, hover/disabled aware) ──

    private Region vseparator() {
        Region sep = new Region();
        sep.setPrefWidth(1);
        sep.setMaxWidth(1);
        sep.setPrefHeight(24);
        sep.setMaxHeight(24);
        sep.setStyle("-fx-background-color: " + BORDER + ";");
        return sep;
    }

    /** Wraps a vector icon factory (Color → Group, 24-unit viewBox) into a Node factory at toolbar icon size. */
    private Function<Color, Node> iconFactory(Function<Color, Group> vector) {
        return color -> scaledIcon(vector.apply(color), 18);
    }

    /** Adapter for a unicode glyph (Settings ⚙) that doesn't need a redrawn vector icon. */
    private Function<Color, Node> glyphIcon(String glyph) {
        return color -> {
            Label l = new Label(glyph);
            l.setFont(Font.font("System", FontWeight.BOLD, 16));
            l.setTextFill(color);
            return l;
        };
    }

    /** Icon-only button (no caption) — used for the Settings gear, top-right of the header. */
    private Button iconOnlyButton(Function<Color, Node> iconFactory, String tooltipText, Color hoverColor) {
        Button btn = new Button();
        btn.setGraphic(iconFactory.apply(Color.web(TEXT_MUTED)));
        btn.setTooltip(new Tooltip(tooltipText));
        String idleStyle  = "-fx-background-color: transparent; -fx-cursor: hand; -fx-padding: 3;";
        String hoverStyle = "-fx-background-color: " + BG_ELEVATED + "; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 3;";
        btn.setStyle(idleStyle);
        btn.setOnMouseEntered(e -> {
            if (btn.isDisabled()) return;
            btn.setGraphic(iconFactory.apply(hoverColor));
            btn.setStyle(hoverStyle);
        });
        btn.setOnMouseExited(e -> {
            if (btn.isDisabled()) return;
            btn.setGraphic(iconFactory.apply(Color.web(TEXT_MUTED)));
            btn.setStyle(idleStyle);
        });
        return btn;
    }

    private Button iconButton(Function<Color, Node> iconFactory, String captionText, String tooltipText, Color hoverColor) {
        Button btn = new Button(captionText);
        btn.setGraphic(iconFactory.apply(Color.web(TEXT_MUTED)));
        btn.setContentDisplay(ContentDisplay.TOP);
        btn.setGraphicTextGap(3);
        btn.setFont(Font.font("System", 10.5));
        btn.setTextFill(Color.web(TEXT_MUTED));
        btn.setTooltip(new Tooltip(tooltipText));
        String idleStyle  = "-fx-background-color: transparent; -fx-cursor: hand; -fx-padding: 5 8 6 8;";
        String hoverStyle = "-fx-background-color: " + BG_ELEVATED + "; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 5 8 6 8;";
        btn.setStyle(idleStyle);
        btn.setOnMouseEntered(e -> {
            if (btn.isDisabled()) return;
            btn.setGraphic(iconFactory.apply(hoverColor));
            btn.setTextFill(Color.web(TEXT_SUB));
            btn.setStyle(hoverStyle);
        });
        btn.setOnMouseExited(e -> {
            if (btn.isDisabled()) return;
            btn.setGraphic(iconFactory.apply(Color.web(TEXT_MUTED)));
            btn.setTextFill(Color.web(TEXT_MUTED));
            btn.setStyle(idleStyle);
        });
        return btn;
    }

    private Group scaledIcon(Group inner, double targetSize) {
        inner.getTransforms().add(new Scale(targetSize / 24.0, targetSize / 24.0, 0, 0));
        return new Group(inner);
    }

    // ── Vector icon primitives ───────────────────────────────────────────────────

    private static Line ln(double x1, double y1, double x2, double y2, Color c, double sw) {
        Line l = new Line(x1, y1, x2, y2);
        l.setStroke(c);
        l.setStrokeWidth(sw);
        l.setStrokeLineCap(StrokeLineCap.ROUND);
        return l;
    }

    private static Circle circ(double cx, double cy, double r, Color c, double sw, boolean filled) {
        Circle circle = new Circle(cx, cy, r);
        if (filled) {
            circle.setFill(c);
            circle.setStroke(null);
        } else {
            circle.setFill(Color.TRANSPARENT);
            circle.setStroke(c);
            circle.setStrokeWidth(sw);
        }
        return circle;
    }

    private static Rectangle rect(double x, double y, double w, double h, double arc, Color c, double sw) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setArcWidth(arc);
        r.setArcHeight(arc);
        r.setFill(Color.TRANSPARENT);
        r.setStroke(c);
        r.setStrokeWidth(sw);
        return r;
    }

    private static Path openPath(Color c, double sw, double... coords) {
        Path p = new Path();
        p.getElements().add(new MoveTo(coords[0], coords[1]));
        for (int i = 2; i < coords.length; i += 2) p.getElements().add(new LineTo(coords[i], coords[i + 1]));
        p.setStroke(c);
        p.setStrokeWidth(sw);
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        p.setFill(Color.TRANSPARENT);
        return p;
    }

    private static Polygon triangle(Color c, double... coords) {
        Polygon t = new Polygon(coords);
        t.setFill(c);
        t.setStroke(null);
        return t;
    }

    // ── Vector icons (24-unit viewBox, recolorable) ──────────────────────────────

    private Group iconStamp(Color c) {
        Group g = new Group();
        g.getChildren().addAll(
            rect(3, 3, 13, 16, 4, c, 1.7),
            circ(9.5, 9, 3.1, c, 1.7, false),
            openPath(c, 1.7, 8, 9, 9.1, 10.1, 11.1, 7.6),
            ln(9.5, 12.1, 9.5, 17, c, 1.7),
            ln(15.3, 15.3, 19.5, 19.5, c, 1.7),
            circ(18.9, 14.8, 1.9, c, 1.6, false)
        );
        return g;
    }

    private Group iconUpload(Color c) {
        Group g = new Group();
        Path cloud = new Path(
            new MoveTo(7, 17.3),
            new ArcTo(4, 4, 0, 6.5, 9.33, false, true),
            new ArcTo(5.5, 5.5, 0, 17.2, 7.9, false, true),
            new ArcTo(4.25, 4.25, 0, 16.5, 17.3, false, true),
            new LineTo(7, 17.3),
            new ClosePath()
        );
        cloud.setStroke(c);
        cloud.setStrokeWidth(1.7);
        cloud.setFill(Color.TRANSPARENT);
        cloud.setStrokeLineCap(StrokeLineCap.ROUND);
        cloud.setStrokeLineJoin(StrokeLineJoin.ROUND);
        g.getChildren().addAll(
            cloud,
            ln(12, 15, 12, 9, c, 1.7),
            openPath(c, 1.7, 9.3, 11.3, 12, 8.6, 14.7, 11.3)
        );
        return g;
    }

    private Group iconReplay(Color c) {
        Group g = new Group();
        Path arc = new Path(new MoveTo(6.6, 5.4), new ArcTo(8, 8, 0, 15.3, 18.4, true, false));
        arc.setStroke(c);
        arc.setStrokeWidth(2.2);
        arc.setFill(Color.TRANSPARENT);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        g.getChildren().addAll(
            arc,
            triangle(c, 13.4, 4.1, 16.7, 5.6, 15.7, 9.0),
            triangle(c, 9.2, 9.1, 9.2, 15.8, 14.9, 12.45)
        );
        return g;
    }

    private Group iconVersions(Color c) {
        Group g = new Group();
        Path page2 = new Path(
            new MoveTo(7, 8.8), new LineTo(18, 8.8), new LineTo(21, 11.8), new LineTo(21, 21.5), new LineTo(17.5, 21.5)
        );
        page2.setStroke(c);
        page2.setStrokeWidth(1.5);
        page2.setFill(Color.TRANSPARENT);
        page2.setOpacity(0.5);
        page2.setStrokeLineCap(StrokeLineCap.ROUND);
        page2.setStrokeLineJoin(StrokeLineJoin.ROUND);

        Path page1 = new Path(
            new MoveTo(4.5, 6.8), new LineTo(13.5, 6.8), new LineTo(16.5, 9.8),
            new LineTo(16.5, 19.5), new LineTo(4.5, 19.5), new ClosePath()
        );
        page1.setStroke(c);
        page1.setStrokeWidth(1.5);
        page1.setFill(Color.TRANSPARENT);
        page1.setStrokeLineCap(StrokeLineCap.ROUND);
        page1.setStrokeLineJoin(StrokeLineJoin.ROUND);

        g.getChildren().addAll(
            page2, page1,
            ln(6.8, 12.3, 13.0, 12.3, c, 1.5),
            ln(6.8, 15.2, 13.0, 15.2, c, 1.5),
            ln(6.8, 18.1, 11.2, 18.1, c, 1.5)
        );
        return g;
    }

    private Group iconReport(Color c) {
        Group g = new Group();
        g.getChildren().addAll(
            rect(4.5, 3.5, 15, 18, 3, c, 1.6),
            rect(9, 2, 6, 3, 1.5, c, 1.6),
            rect(7.4, 8.4, 2, 2, 0.6, c, 1.6),
            ln(11.3, 9.4, 17.5, 9.4, c, 1.6),
            rect(7.4, 12.4, 2, 2, 0.6, c, 1.6),
            ln(11.3, 13.4, 17.5, 13.4, c, 1.6),
            rect(7.4, 16.4, 2, 2, 0.6, c, 1.6),
            ln(11.3, 17.4, 17.5, 17.4, c, 1.6)
        );
        return g;
    }

    private Group iconImport(Color c) {
        Group g = new Group();
        g.getChildren().addAll(
            circ(12, 12, 9.3, c, 1.7, false),
            ln(12, 7.2, 12, 14.2, c, 1.7),
            openPath(c, 1.7, 8.6, 11.2, 12, 14.6, 15.4, 11.2),
            ln(8, 16.6, 16, 16.6, c, 1.7)
        );
        return g;
    }

    /** Circular "undo/refresh" arrow — reads as Reset at a glance, distinct from Import's downward arrow. */
    private Group iconReset(Color c) {
        Arc arc = new Arc(12, 12, 7.6, 7.6, 35, 275);
        arc.setType(ArcType.OPEN);
        arc.setFill(Color.TRANSPARENT);
        arc.setStroke(c);
        arc.setStrokeWidth(1.7);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);

        Polygon arrowHead = new Polygon(
            17.9, 5.9,
            21.3, 6.9,
            19.5, 9.9
        );
        arrowHead.setFill(c);

        Group g = new Group(arc, arrowHead);
        return g;
    }

    private Group iconDashboard(Color c) {
        Group g = new Group();
        g.getChildren().addAll(
            rect(2, 3, 16, 14, 3, c, 1.5),
            ln(5.2, 6.4, 9.6, 6.4, c, 1.5),
            ln(5.2, 9, 8.4, 9, c, 1.5),
            ln(5.2, 11.6, 9.1, 11.6, c, 1.5),
            openPath(c, 1.5, 12, 10.6, 13.8, 8.9, 15.3, 10.1, 17.3, 7.9),
            circ(17.2, 6.1, 1.15, c, 0, true),
            ln(5.6, 13.6, 5.6, 15.0, c, 1.5),
            ln(8, 12.4, 8, 15.0, c, 1.5),
            ln(10.4, 13.1, 10.4, 15.0, c, 1.5),
            circ(16.5, 16.5, 5, c, 1.4, false),
            circ(16.5, 16.5, 2.7, c, 1.4, false),
            circ(16.5, 16.5, 0.9, c, 0, true)
        );
        return g;
    }

    private Group iconMedal(Color c) {
        Group g = new Group();
        g.getChildren().addAll(
            openPath(c, 1.6, 7, 15.4, 4.7, 20.4, 8.3, 19.4, 10, 22.4, 12, 17.8),
            openPath(c, 1.6, 17, 15.4, 19.3, 20.4, 15.7, 19.4, 14, 22.4, 12, 17.8),
            circ(12, 10, 7.3, c, 1.6, false),
            openPath(c, 1.6, 8.8, 10.2, 10.8, 12.2, 14.8, 8.0)
        );
        return g;
    }

    // ── Logo helpers ─────────────────────────────────────────────────────────

    static Image loadLogo() {
        try (var is = QTracePanel.class.getResourceAsStream("/io/qtrace/logo.png")) {
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
            if (QTracePluginManager.isEntitled()) {
                stage.setMinWidth(560);
                if (stage.getWidth() < 560) stage.setWidth(560);
            } else {
                stage.setMinWidth(380);
                if (stage.getWidth() < 380) stage.setWidth(400);
            }
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

    /** Enable/disable the ☁ push button (Compliance only — no-op in Core). */
    public void setPushEnabled(boolean enabled) {
        if (btnPush == null) return;
        Platform.runLater(() -> {
            btnPush.setDisable(!enabled);
            btnPush.setOpacity(enabled ? 1.0 : 0.45);
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

    /** Toggle recording indicator dot (status row) and the passive Recording/Paused badge (header). */
    public void setRecordingActive(boolean active) {
        Platform.runLater(() -> {
            if (active) {
                statusDot.setFill(Color.web(GREEN));
                statusLabel.setText("Recording");
                statusLabel.setTextFill(Color.web(GREEN));
                captureDot.setFill(Color.web(RED));
                captureLabel.setText("Recording");
                captureLabel.setTextFill(Color.web(RED));
                startCaptureBlink();
            } else {
                statusDot.setFill(Color.web(TEXT_MUTED));
                statusLabel.setText("Idle — awaiting recording");
                statusLabel.setTextFill(Color.web(TEXT_SUB));
                captureDot.setFill(Color.web(TEXT_MUTED));
                captureLabel.setText("Paused");
                captureLabel.setTextFill(Color.web(TEXT_MUTED));
                stopCaptureBlink();
            }
        });
    }

    /** Enable the "Stamp" CTA once steps are captured. */
    public void setRecordReady(boolean ready) {
        Platform.runLater(() -> {
            btnRecord.setDisable(!ready);
            btnRecord.setOpacity(ready ? 1.0 : 0.45);
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
