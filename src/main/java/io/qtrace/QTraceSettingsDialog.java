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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import javafx.stage.Modality;
import javafx.stage.Stage;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Modal dialog for configuring QTrace export paths.
 * Call {@link #show(Stage)} to open; changes are persisted on OK.
 */
public class QTraceSettingsDialog {

    // Catppuccin Mocha — matches QTracePanel
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";
    private static final String ORANGE     = "#fab387";
    private static final String RED        = "#f38ba8";
    private static final String PORTAL_URL = "https://qtrace.ca/portal";

    public static void show(Stage owner) { show(owner, (Project<?>) null); }

    public static void show(QuPathGUI qupath) { show(qupath.getStage(), qupath.getProject()); }

    public static void show(Stage owner, Project<?> project) {
        Path projectBaseDir = (project != null && project.getPath() != null)
            ? project.getPath().getParent() : null;

        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("QTrace — Settings");
        dlg.setResizable(false);

        QTraceConfig cfg = QTraceConfig.get();

        // ── Path rows ──────────────────────────────────────────────────────────
        TextField tfExport      = pathField(cfg.rawExportDir());
        TextField tfClassifier  = pathField(cfg.rawClassifierDir());
        TextField tfTraining    = pathField(cfg.rawTrainingDir());
        TextField tfLogs        = pathField(cfg.rawLogsDir());

        GridPane grid = new GridPane();
        grid.setId("settings-paths-grid"); // looked up by the screenshot harness — see ScreenshotHarness
        grid.setHgap(8);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 20, 12, 20));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(140);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        ColumnConstraints btnCol   = new ColumnConstraints();
        btnCol.setMinWidth(70);
        grid.getColumnConstraints().addAll(labelCol, fieldCol, btnCol);

        addRow(grid, 0, ".qtrace + CSV export",        tfExport,      dlg);
        addRow(grid, 1, "Classifier Git tracking",     tfClassifier,  dlg);
        addRow(grid, 2, "Training GeoJSON",            tfTraining,    dlg);
        addRow(grid, 3, "Player replay logs",          tfLogs,        dlg);

        // Hint
        Label hint = new Label("Leave blank to use default: " + QTraceConfig.defaultDirString()
            + "  (replay logs default to ~/.qTrace/replay-logs/)");
        hint.setTextFill(Color.web(TEXT_MUTED));
        hint.setFont(Font.font("System", 10));
        hint.setWrapText(true);
        hint.setMaxWidth(440);

        // ── Project Folder mode — overrides all paths above with <project>/qTrace/ ──────────
        CheckBox chkProjectFolder = new CheckBox("Use Project Folder");
        chkProjectFolder.setSelected(cfg.isUseProjectFolder());
        chkProjectFolder.setTextFill(Color.web(TEXT_SUB));

        Label projectFolderHint = new Label(
            "When enabled (default), qTrace stores its output under <project folder>/qTrace/ — "
          + "with trace/, geoJson/, logs/ and gitTrack/ subfolders, created automatically on Save "
          + "or on Stamp — instead of the paths below, which become read-only. Falls back to the "
          + "paths below when no QuPath project is open.");
        projectFolderHint.setTextFill(Color.web(TEXT_MUTED));
        projectFolderHint.setFont(Font.font("System", 10));
        projectFolderHint.setWrapText(true);
        projectFolderHint.setMaxWidth(440);

        Button btnOpenProject = flatButton("Open Project Folder", BLUE);
        btnOpenProject.setId("settings-open-project-folder");
        btnOpenProject.setDisable(projectBaseDir == null);
        btnOpenProject.setOnAction(e -> {
            if (projectBaseDir == null) return;
            Path qDir = QTraceConfig.projectQTraceDir(projectBaseDir);
            BrowserOpener.open(java.nio.file.Files.isDirectory(qDir) ? qDir.toString() : projectBaseDir.toString());
        });
        btnOpenProject.visibleProperty().bind(chkProjectFolder.selectedProperty());
        btnOpenProject.managedProperty().bind(chkProjectFolder.selectedProperty());

        Region projectFolderSpacer = new Region();
        HBox.setHgrow(projectFolderSpacer, Priority.ALWAYS);
        HBox projectFolderHeader = new HBox(8, chkProjectFolder, projectFolderSpacer, btnOpenProject);
        projectFolderHeader.setAlignment(Pos.CENTER_LEFT);

        VBox projectFolderBox = new VBox(4, projectFolderHeader, projectFolderHint);
        projectFolderBox.setPadding(new Insets(16, 20, 12, 20));

        // Project Folder mode redirects all four paths above — fields become read-only and
        // display the resolved <project>/qTrace/<subdir> path (or a prompt when no project is open).
        Runnable refreshPathFields = () -> {
            boolean useProj = chkProjectFolder.isSelected();
            tfExport.setDisable(useProj);
            tfClassifier.setDisable(useProj);
            tfTraining.setDisable(useProj);
            tfLogs.setDisable(useProj);
            if (useProj) {
                if (projectBaseDir != null) {
                    tfExport.setText(QTraceConfig.resolveDir(true, projectBaseDir, QTraceConfig.TRACE_SUBDIR, null).toString());
                    tfClassifier.setText(QTraceConfig.resolveDir(true, projectBaseDir, QTraceConfig.GITTRACK_SUBDIR, null).toString());
                    tfTraining.setText(QTraceConfig.resolveDir(true, projectBaseDir, QTraceConfig.GEOJSON_SUBDIR, null).toString());
                    tfLogs.setText(QTraceConfig.resolveDir(true, projectBaseDir, QTraceConfig.LOGS_SUBDIR, null).toString());
                } else {
                    for (TextField tf : new TextField[] { tfExport, tfClassifier, tfTraining, tfLogs }) {
                        tf.setText("");
                        tf.setPromptText("(open a QuPath project to resolve this path)");
                    }
                }
            } else {
                tfExport.setText(cfg.rawExportDir());
                tfClassifier.setText(cfg.rawClassifierDir());
                tfTraining.setText(cfg.rawTrainingDir());
                tfLogs.setText(cfg.rawLogsDir());
            }
        };
        chkProjectFolder.selectedProperty().addListener((obs, was, sel) -> refreshPathFields.run());
        refreshPathFields.run();

        // ── Validator section ──────────────────────────────────────────────────
        TextField tfValidator = new TextField(cfg.getValidatorName());
        tfValidator.setId("settings-validator-field"); // looked up by the screenshot harness — see ScreenshotHarness
        tfValidator.setPromptText("e.g. Dr. Lastname  —  leave blank to enter each time");
        tfValidator.setPrefHeight(30);
        tfValidator.setStyle(
            "-fx-background-color: " + BG_SURFACE + ";"
          + "-fx-text-fill: " + TEXT_MAIN + ";"
          + "-fx-prompt-text-fill: " + TEXT_MUTED + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4;"
          + "-fx-background-radius: 4;"
          + "-fx-font-size: 11;"
        );

        GridPane validatorGrid = new GridPane();
        validatorGrid.setHgap(8);
        validatorGrid.setVgap(12);
        validatorGrid.setPadding(new Insets(4, 20, 12, 20));
        validatorGrid.getColumnConstraints().addAll(labelCol, fieldCol);

        Label validatorLbl = new Label("Validator's name");
        validatorLbl.setTextFill(Color.web(TEXT_SUB));
        validatorLbl.setFont(Font.font("System", FontWeight.NORMAL, 12));
        validatorGrid.add(validatorLbl, 0, 0);
        validatorGrid.add(tfValidator,  1, 0);

        Label emailLbl = new Label("Account email");
        emailLbl.setTextFill(Color.web(TEXT_SUB));
        emailLbl.setFont(Font.font("System", FontWeight.NORMAL, 12));
        TextField tfEmail = new TextField();
        tfEmail.setPromptText("—");
        tfEmail.setPrefHeight(30);
        tfEmail.setEditable(false);
        tfEmail.setDisable(true);
        tfEmail.setStyle(
            "-fx-background-color: " + BG_SURFACE + ";"
          + "-fx-text-fill: " + TEXT_MAIN + ";"
          + "-fx-prompt-text-fill: " + TEXT_MUTED + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4;"
          + "-fx-background-radius: 4;"
          + "-fx-font-size: 11;"
        );
        validatorGrid.add(emailLbl, 0, 1);
        validatorGrid.add(tfEmail,  1, 1);

        // ── Compliance License section ─────────────────────────────────────────
        TextField tfLicense = new TextField(cfg.getLicensePath());
        tfLicense.setPromptText("(no certificate loaded)");
        tfLicense.setPrefHeight(30);
        tfLicense.setEditable(false);
        tfLicense.setStyle(
            "-fx-background-color: " + BG_SURFACE + ";"
          + "-fx-text-fill: " + TEXT_MAIN + ";"
          + "-fx-prompt-text-fill: " + TEXT_MUTED + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4;"
          + "-fx-background-radius: 4;"
          + "-fx-font-size: 11;"
        );

        Label licenseStatusLbl = new Label();
        licenseStatusLbl.setFont(Font.font("System", FontWeight.NORMAL, 11));
        licenseStatusLbl.setWrapText(true);
        licenseStatusLbl.setMaxWidth(440);

        // Validate and display status for current path
        updateLicenseStatus(licenseStatusLbl, cfg.getLicensePath(), tfValidator, tfEmail);

        Button btnBrowseLicense = flatButton("Browse…", TEXT_MUTED);
        btnBrowseLicense.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select your certificate file (.qtlicense)");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("qTrace identity certificate", "*.qtlicense"));
            String current = tfLicense.getText().strip();
            if (!current.isEmpty()) {
                File f = new File(current);
                if (f.getParentFile() != null && f.getParentFile().isDirectory())
                    fc.setInitialDirectory(f.getParentFile());
            }
            File chosen = fc.showOpenDialog(dlg);
            if (chosen != null) {
                tfLicense.setText(chosen.getAbsolutePath());
                updateLicenseStatus(licenseStatusLbl, chosen.getAbsolutePath(), tfValidator, tfEmail);
            }
        });

        Button btnGetLicense = flatButton("🔗 Get a certificate", BLUE);
        btnGetLicense.setOnAction(e -> BrowserOpener.open(PORTAL_URL));

        GridPane licenseGrid = new GridPane();
        licenseGrid.setHgap(8);
        licenseGrid.setVgap(10);
        licenseGrid.setPadding(new Insets(4, 20, 8, 20));

        ColumnConstraints lcLabelCol = new ColumnConstraints();
        lcLabelCol.setMinWidth(140);
        ColumnConstraints lcFieldCol = new ColumnConstraints();
        lcFieldCol.setHgrow(Priority.ALWAYS);
        lcFieldCol.setFillWidth(true);
        ColumnConstraints lcBtn1Col  = new ColumnConstraints();
        lcBtn1Col.setMinWidth(70);
        ColumnConstraints lcBtn2Col  = new ColumnConstraints();
        lcBtn2Col.setMinWidth(90);
        licenseGrid.getColumnConstraints().addAll(lcLabelCol, lcFieldCol, lcBtn1Col, lcBtn2Col);

        Label licenseLbl = new Label("Certificate file (.qtlicense)");
        licenseLbl.setTextFill(Color.web(TEXT_SUB));
        licenseLbl.setFont(Font.font("System", FontWeight.NORMAL, 12));
        licenseGrid.add(licenseLbl,      0, 0);
        licenseGrid.add(tfLicense,       1, 0);
        licenseGrid.add(btnBrowseLicense,2, 0);
        licenseGrid.add(btnGetLicense,   3, 0);
        licenseGrid.add(licenseStatusLbl, 1, 1, 3, 1);

        // ── Detection correction prompting ──────────────────────────────────────
        CheckBox chkDetectionNote = new CheckBox(
            "Prompt for a note when detections or annotations are manually deleted (or detections split)");
        chkDetectionNote.setSelected(cfg.isPromptDetectionNote());
        chkDetectionNote.setTextFill(Color.web(TEXT_SUB));
        chkDetectionNote.setWrapText(true);
        chkDetectionNote.setTooltip(hintTooltip(
            "When disabled, corrections are logged silently with no note prompt. "
          + "Either way, every deletion/split is recorded in the .qtrace sidecar."));

        CheckBox chkUnstampedReminder = new CheckBox(
            "Prompt for a note if modifications not stamped has been detected when you are closing an image");
        chkUnstampedReminder.setSelected(cfg.isPromptUnstampedReminder());
        chkUnstampedReminder.setTextFill(Color.web(TEXT_SUB));
        chkUnstampedReminder.setWrapText(true);
        chkUnstampedReminder.setTooltip(hintTooltip(
            "When disabled, closing or switching away from an image with unstamped modifications "
          + "happens silently — no prompt. With autosave on, the work is still kept as an unstamped session."));

        CheckBox chkAutosave = new CheckBox(QTraceI18n.t("settings.autosave"));
        chkAutosave.setSelected(cfg.isAutosaveEnabled());
        chkAutosave.setTextFill(Color.web(TEXT_SUB));
        chkAutosave.setWrapText(true);
        chkAutosave.setTooltip(hintTooltip(QTraceI18n.t("settings.autosave.hint")));

        // ── Security (activity report) — folded into Preferences ────────────────
        CheckBox chkReportConfirm = new CheckBox(QTraceI18n.t("settings.security.confirm"));
        chkReportConfirm.setSelected(cfg.isReportConfirmBeforeSend());
        chkReportConfirm.setTextFill(Color.web(TEXT_SUB));
        chkReportConfirm.setWrapText(true);
        chkReportConfirm.setTooltip(hintTooltip(QTraceI18n.t("settings.security.confirm.hint")));

        CheckBox chkPseudonymize = new CheckBox(QTraceI18n.t("settings.security.pseudonymize"));
        chkPseudonymize.setDisable(true);   // shown now, implemented later
        chkPseudonymize.setTextFill(Color.web(TEXT_MUTED));
        Label pseudoSoon = new Label(QTraceI18n.t("report.confirm.soon"));
        pseudoSoon.setTextFill(Color.web(TEXT_MUTED));
        pseudoSoon.setFont(Font.font("System", 10));
        HBox pseudoRow = new HBox(8, chkPseudonymize, pseudoSoon);
        pseudoRow.setAlignment(Pos.CENTER_LEFT);

        Label langLabel = new Label(QTraceI18n.t("settings.security.language"));
        langLabel.setTextFill(Color.web(TEXT_SUB));
        langLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
        ComboBox<String> langBox = new ComboBox<>();
        for (String[] l : ReportLanguages.LANGS) langBox.getItems().add(l[0]);
        langBox.setConverter(new StringConverter<>() {
            @Override public String toString(String code) { return code == null ? "" : ReportLanguages.label(code); }
            @Override public String fromString(String s) { return s; }
        });
        langBox.setValue(cfg.getReportLanguage());
        HBox langRow = new HBox(8, langLabel, langBox);
        langRow.setAlignment(Pos.CENTER_LEFT);

        VBox captureBox = new VBox(10,
            subTitle("General"),
            chkDetectionNote, chkUnstampedReminder,
            subTitle("Security"),
            chkReportConfirm, langRow, pseudoRow);
        captureBox.setPadding(new Insets(4, 20, 8, 20));

        // ── Autosave (live draft + image copy for crash recovery) ───────────────
        Label snapIntro = new Label(QTraceI18n.t("settings.snapshot.intro"));
        snapIntro.setTextFill(Color.web(TEXT_SUB));
        snapIntro.setFont(Font.font("System", 11));
        snapIntro.setWrapText(true);

        ToggleGroup snapMode = new ToggleGroup();
        RadioButton rbAuto   = new RadioButton(QTraceI18n.f("settings.snapshot.auto", io.qtrace.draft.SnapshotPolicy.DEFAULT_INTERVAL_S));
        RadioButton rbCustom = new RadioButton(QTraceI18n.t("settings.snapshot.custom"));
        for (RadioButton rb : new RadioButton[]{rbAuto, rbCustom}) {
            rb.setToggleGroup(snapMode);
            rb.setTextFill(Color.web(TEXT_SUB));
            rb.setWrapText(true);
        }
        (cfg.isSnapshotCustom() ? rbCustom : rbAuto).setSelected(true);

        Spinner<Integer> spThreshold = new Spinner<>(io.qtrace.draft.SnapshotPolicy.MIN_THRESHOLD_MB,
            io.qtrace.draft.SnapshotPolicy.MAX_THRESHOLD_MB, cfg.getSnapshotLargeThresholdMb(), 10);
        Spinner<Integer> spInterval  = new Spinner<>(io.qtrace.draft.SnapshotPolicy.MIN_INTERVAL_S,
            io.qtrace.draft.SnapshotPolicy.MAX_INTERVAL_S, cfg.getSnapshotLargeIntervalSec(), 10);
        for (Spinner<Integer> sp : java.util.List.of(spThreshold, spInterval)) {
            sp.setEditable(true);
            sp.setPrefWidth(110);
            // A typed value is only committed on Enter — also commit it when leaving the field.
            sp.focusedProperty().addListener((o, was, now) -> { if (!now) sp.increment(0); });
        }
        CheckBox chkKeyOnly = new CheckBox(QTraceI18n.t("settings.snapshot.keyOnly"));
        chkKeyOnly.setSelected(cfg.isSnapshotLargeKeyStepsOnly());
        chkKeyOnly.setTextFill(Color.web(TEXT_SUB));
        chkKeyOnly.setWrapText(true);
        chkKeyOnly.setTooltip(hintTooltip(QTraceI18n.t("settings.snapshot.keyOnly.hint")));

        Label lblThreshold = new Label(QTraceI18n.t("settings.snapshot.threshold"));
        Label lblInterval  = new Label(QTraceI18n.t("settings.snapshot.interval"));
        for (Label l : new Label[]{lblThreshold, lblInterval}) {
            l.setTextFill(Color.web(TEXT_SUB));
            l.setFont(Font.font("System", 12));
            l.setWrapText(true);
        }
        GridPane snapGrid = new GridPane();
        snapGrid.setHgap(10);
        snapGrid.setVgap(8);
        snapGrid.setPadding(new Insets(0, 0, 0, 24));
        snapGrid.add(lblThreshold, 0, 0); snapGrid.add(spThreshold, 1, 0);
        snapGrid.add(lblInterval,  0, 1); snapGrid.add(spInterval,  1, 1);
        snapGrid.add(chkKeyOnly,   0, 2, 2, 1);

        Label consequences = new Label();
        consequences.setWrapText(true);
        consequences.setFont(Font.font("System", 11));
        consequences.setTextFill(Color.web(TEXT_SUB));
        consequences.setPadding(new Insets(8, 10, 8, 10));
        consequences.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-background-radius: 6;"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6;");
        consequences.setMaxWidth(Double.MAX_VALUE);

        Runnable refreshSnapshot = () -> {
            boolean on     = chkAutosave.isSelected();
            boolean custom = rbCustom.isSelected();
            rbAuto.setDisable(!on);
            rbCustom.setDisable(!on);
            snapGrid.setDisable(!on || !custom);
            var p = custom
                ? io.qtrace.draft.SnapshotPolicy.custom(spThreshold.getValue(), spInterval.getValue(), chkKeyOnly.isSelected())
                : io.qtrace.draft.SnapshotPolicy.auto();
            if (!custom) {   // show what Auto actually uses
                spThreshold.getValueFactory().setValue(p.thresholdMb());
                spInterval.getValueFactory().setValue(p.intervalS());
                chkKeyOnly.setSelected(p.largeKeyStepsOnly());
            }
            consequences.setText(on ? snapshotConsequences(p) : QTraceI18n.t("settings.snapshot.off"));
        };
        // The user's custom values, kept across an Auto ↔ Custom round trip (Auto shows its own).
        int[] customValues = {cfg.getSnapshotLargeThresholdMb(), cfg.getSnapshotLargeIntervalSec(),
                              cfg.isSnapshotLargeKeyStepsOnly() ? 1 : 0};
        Runnable recordCustom = () -> {
            if (!rbCustom.isSelected()) return;
            customValues[0] = spThreshold.getValue();
            customValues[1] = spInterval.getValue();
            customValues[2] = chkKeyOnly.isSelected() ? 1 : 0;
        };
        chkAutosave.setOnAction(e -> refreshSnapshot.run());
        spThreshold.valueProperty().addListener((o, a, b) -> { recordCustom.run(); refreshSnapshot.run(); });
        spInterval.valueProperty().addListener((o, a, b) -> { recordCustom.run(); refreshSnapshot.run(); });
        chkKeyOnly.setOnAction(e -> { recordCustom.run(); refreshSnapshot.run(); });
        snapMode.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == rbCustom) {
                int[] saved = customValues.clone();   // setValue() below re-records as it goes
                spThreshold.getValueFactory().setValue(saved[0]);
                spInterval.getValueFactory().setValue(saved[1]);
                chkKeyOnly.setSelected(saved[2] == 1);
                recordCustom.run();
            }
            refreshSnapshot.run();
        });
        refreshSnapshot.run();

        VBox autosaveBox = new VBox(10,
            chkAutosave,
            subTitle(QTraceI18n.t("settings.snapshot.title")),
            snapIntro, rbAuto, rbCustom, snapGrid,
            subTitle(QTraceI18n.t("settings.snapshot.consequences")),
            consequences);
        autosaveBox.setPadding(new Insets(4, 20, 8, 20));

        // ── Buttons ────────────────────────────────────────────────────────────
        Button btnReset  = flatButton("Reset all to default", TEXT_MUTED);
        Button btnCancel = flatButton("Cancel",               TEXT_SUB);
        Button btnOk     = solidButton("Save",                BLUE);

        btnReset.setOnAction(e -> {
            chkProjectFolder.setSelected(true);
            refreshPathFields.run();
            tfValidator.clear();
            tfLicense.clear();
            updateLicenseStatus(licenseStatusLbl, "", tfValidator, tfEmail);
            chkDetectionNote.setSelected(true);
            chkUnstampedReminder.setSelected(true);
            chkAutosave.setSelected(true);
            rbAuto.setSelected(true);
            customValues[0] = io.qtrace.draft.SnapshotPolicy.DEFAULT_THRESHOLD_MB;
            customValues[1] = io.qtrace.draft.SnapshotPolicy.DEFAULT_INTERVAL_S;
            customValues[2] = 0;
            refreshSnapshot.run();
        });

        btnCancel.setOnAction(e -> dlg.close());

        btnOk.setOnAction(e -> {
            // When Project Folder mode is on, the fields above just display the resolved
            // <project>/qTrace/<subdir> path — don't clobber the configured fallback with it.
            if (!chkProjectFolder.isSelected()) {
                cfg.setExportDir(tfExport.getText());
                cfg.setClassifierDir(tfClassifier.getText());
                cfg.setTrainingDir(tfTraining.getText());
                cfg.setLogsDir(tfLogs.getText());
            }
            cfg.setUseProjectFolder(chkProjectFolder.isSelected());
            cfg.setValidatorName(tfValidator.getText());
            cfg.setLicensePath(tfLicense.getText());
            cfg.setReportConfirmBeforeSend(chkReportConfirm.isSelected());
            if (langBox.getValue() != null) cfg.setReportLanguage(langBox.getValue());
            cfg.setPromptDetectionNote(chkDetectionNote.isSelected());
            cfg.setPromptUnstampedReminder(chkUnstampedReminder.isSelected());
            cfg.setAutosaveEnabled(chkAutosave.isSelected());
            if (rbCustom.isSelected())
                cfg.setSnapshotSettings(true, spThreshold.getValue(), spInterval.getValue(), chkKeyOnly.isSelected());
            else
                cfg.setSnapshotSettings(false, customValues[0], customValues[1], customValues[2] == 1);
            cfg.save();
            if (chkProjectFolder.isSelected() && projectBaseDir != null) {
                try { QTraceConfig.createProjectDirs(projectBaseDir); } catch (IOException ignored) {}
            }
            dlg.close();
        });

        HBox buttonRow = new HBox(8, btnReset, spacer(), btnCancel, btnOk);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(8, 20, 16, 20));
        buttonRow.setStyle("-fx-background-color: " + BG_BASE + ";");

        // ── Pages ──────────────────────────────────────────────────────────────
        VBox.setMargin(hint, new Insets(0, 20, 8, 20));

        VBox pageIdentity = new VBox(14, validatorGrid, buildDigitalIdentityCard(cfg), buildCredentialsRow());
        VBox pageLicense    = new VBox(licenseGrid);
        VBox pagePaths      = new VBox(projectFolderBox, grid, hint);
        VBox pagePreferences = captureBox;
        VBox pageAutosave    = autosaveBox;

        Label appearanceSoon = new Label("Theme customization — coming soon.");
        appearanceSoon.setTextFill(Color.web(TEXT_MUTED));
        appearanceSoon.setFont(Font.font("System", 11));
        VBox pageAppearance = new VBox(appearanceSoon);
        pageAppearance.setPadding(new Insets(4, 20, 8, 20));

        // ── Content area: title bar + scrollable page ────────────────────────────
        Label headerLbl = new Label();
        headerLbl.setTextFill(Color.web(TEXT_MAIN));
        headerLbl.setFont(Font.font("System", FontWeight.BOLD, 13));
        headerLbl.setPadding(new Insets(12, 20, 12, 20));
        headerLbl.setMaxWidth(Double.MAX_VALUE);
        headerLbl.setStyle("-fx-background-color: " + BG_SURFACE + ";"
            + "-fx-border-color: transparent transparent " + BORDER + " transparent;"
            + "-fx-border-width: 0 0 1 0;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background: " + BG_BASE + "; -fx-background-color: transparent;");

        VBox centerBox = new VBox(headerLbl, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        centerBox.setStyle("-fx-background-color: " + BG_BASE + ";");

        // ── Sidebar ────────────────────────────────────────────────────────────
        record NavEntry(String title, Node page, Label navLabel) {}
        java.util.List<NavEntry> entries = new java.util.ArrayList<>();
        VBox sidebar = new VBox();
        sidebar.setId("settings-sidebar"); // looked up by the screenshot harness — see ScreenshotHarness
        sidebar.setPrefWidth(190);
        sidebar.setMinWidth(190);
        sidebar.setStyle("-fx-background-color: " + BG_SURFACE + ";");

        VBox pageAbout = QTraceAboutDialog.buildContent();

        Object[][] sections = {
            {"Identity",       pageIdentity},
            {"Certificate",    pageLicense},
            {"Paths",          pagePaths},
            {"Preferences",    pagePreferences},
            {"Autosave",       pageAutosave},
            {"Appearance",     pageAppearance},
            {"About qTrace",   pageAbout},
        };
        // Looked up by the screenshot harness to click into a section without a real mouse —
        // see ScreenshotHarness / tools/screenshots.
        String[] sectionIds = {"identity", "licence", "paths", "preferences", "autosave", "appearance", "about"};

        for (int i = 0; i < sections.length; i++) {
            Object[] s = sections[i];
            String title = (String) s[0];
            Node page = (Node) s[1];
            Label navLabel = new Label(title);
            navLabel.setId("settings-nav-" + sectionIds[i]);
            navLabel.setMaxWidth(Double.MAX_VALUE);
            navLabel.setFont(Font.font("System", 12));
            navLabel.setPadding(new Insets(10, 16, 10, 16));
            navLabel.setStyle("-fx-cursor: hand;");
            entries.add(new NavEntry(title, page, navLabel));
            sidebar.getChildren().add(navLabel);
        }

        java.util.function.Consumer<NavEntry> selectEntry = entry -> {
            headerLbl.setText(entry.title());
            scrollPane.setContent(entry.page());
            for (NavEntry other : entries) {
                boolean selected = other == entry;
                other.navLabel().setTextFill(Color.web(selected ? TEXT_MAIN : TEXT_SUB));
                other.navLabel().setStyle("-fx-cursor: hand; -fx-background-color: "
                    + (selected ? "#242438" : "transparent") + ";");
            }
        };
        for (NavEntry entry : entries) entry.navLabel().setOnMouseClicked(e -> selectEntry.accept(entry));
        selectEntry.accept(entries.get(0));

        // ── Root ───────────────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(centerBox);
        root.setBottom(buttonRow);
        root.setStyle("-fx-background-color: " + BG_BASE + ";");
        root.setPrefSize(780, 500);

        dlg.setScene(new Scene(root));
        dlg.showAndWait();
    }

    // ── Row builder ───────────────────────────────────────────────────────────

    private static void addRow(GridPane grid, int row, String label, TextField field, Stage dlg) {
        Label lbl = new Label(label);
        lbl.setTextFill(Color.web(TEXT_SUB));
        lbl.setFont(Font.font("System", FontWeight.NORMAL, 12));

        Button browse = flatButton("Browse…", TEXT_MUTED);
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select folder — " + label);
            String current = field.getText().strip();
            if (!current.isEmpty()) {
                File dir = new File(current);
                if (dir.isDirectory()) dc.setInitialDirectory(dir);
            }
            File chosen = dc.showDialog(dlg);
            if (chosen != null) field.setText(chosen.getAbsolutePath());
        });

        grid.add(lbl,    0, row);
        grid.add(field,  1, row);
        grid.add(browse, 2, row);
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private static TextField pathField(String value) {
        TextField tf = new TextField(value);
        tf.setPromptText("(default)");
        tf.setPrefHeight(30);
        tf.setStyle(
            "-fx-background-color: " + BG_SURFACE + ";"
          + "-fx-text-fill: " + TEXT_MAIN + ";"
          + "-fx-prompt-text-fill: " + TEXT_MUTED + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4;"
          + "-fx-background-radius: 4;"
          + "-fx-font-size: 11;"
        );
        return tf;
    }

    private static Button flatButton(String text, String color) {
        Button btn = new Button(text);
        btn.setTextFill(Color.web(color));
        btn.setFont(Font.font("System", 12));
        btn.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-cursor: hand;"
          + "-fx-border-color: transparent;"
        );
        return btn;
    }

    private static Button solidButton(String text, String bg) {
        Button btn = new Button(text);
        btn.setFont(Font.font("System", FontWeight.BOLD, 12));
        btn.setPadding(new Insets(6, 18, 6, 18));
        btn.setStyle(
            "-fx-background-color: " + bg + ";"
          + "-fx-text-fill: " + BG_BASE + ";"
          + "-fx-background-radius: 6;"
          + "-fx-cursor: hand;"
        );
        return btn;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    // ── Digital Identity (Compliance) ────────────────────────────────────────

    private static VBox buildDigitalIdentityCard(QTraceConfig cfg) {
        Label status = new Label("Loading digital identity…");
        status.setTextFill(Color.web(TEXT_MUTED));
        status.setFont(Font.font("System", 11));
        status.setWrapText(true);

        VBox card = new VBox(8, status);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setStyle(
            "-fx-background-color: " + BG_SURFACE + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 8;"
          + "-fx-background-radius: 8;"
        );

        VBox section = new VBox(6, subTitle("Digital Identity"), card);
        section.setPadding(new Insets(4, 20, 4, 20));

        QTracePlugin plugin = QTracePluginManager.get();
        if (plugin == null) {
            status.setText("Available with a qTrace identity certificate.");
            return section;
        }
        if (cfg.getLicensePath().isBlank()) {
            status.setText("No certificate loaded — sign in from Extensions > QTrace > Getting started, or load your .qtlicense file in the Certificate section.");
            return section;
        }

        plugin.fetchIdentity().thenAccept(info -> javafx.application.Platform.runLater(() -> {
            card.getChildren().clear();
            if (info == null) {
                status.setText("Could not fetch digital identity — check your certificate and network connection.");
                card.getChildren().add(status);
                return;
            }

            GridPane g = new GridPane();
            g.setHgap(10);
            g.setVgap(6);
            int row = 0;

            g.add(idFieldLabel("Key"), 0, row);
            g.add(monoValue(info.signingKeyPubShort()), 1, row++);

            if (info.anchored()) {
                g.add(idFieldLabel("Anchor tx"), 0, row);
                Label txLbl = monoValue(info.anchorTxHashShort());
                if (info.explorerUrl() != null) {
                    txLbl.setTextFill(Color.web(BLUE));
                    txLbl.setStyle(txLbl.getStyle() + "-fx-cursor: hand; -fx-underline: true;");
                    txLbl.setOnMouseClicked(e -> BrowserOpener.open(info.explorerUrl()));
                }
                g.add(txLbl, 1, row++);

                g.add(idFieldLabel("Anchored"), 0, row);
                g.add(monoValue(info.anchorAt() != null && info.anchorAt().length() >= 10
                    ? info.anchorAt().substring(0, 10) : "—"), 1, row++);
            } else {
                g.add(idFieldLabel("Anchor"), 0, row);
                g.add(monoValue("Pending — anchoring on Polygon can take a few minutes after issuance."), 1, row++);
            }

            Label badgeState = new Label(info.identityPublic()
                ? "✓ Public badge enabled" + (info.badgeUrl() != null ? "  —  " + info.badgeUrl() : "")
                : "Public badge disabled — enable it on qtrace.ca to share your on-chain identity.");
            badgeState.setTextFill(Color.web(info.identityPublic() ? GREEN : TEXT_MUTED));
            badgeState.setFont(Font.font("System", 11));
            badgeState.setWrapText(true);
            badgeState.setMaxWidth(440);

            card.getChildren().addAll(g, badgeState);
        }));

        return section;
    }

    private static Label idFieldLabel(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web(TEXT_MUTED));
        l.setFont(Font.font("System", 11));
        return l;
    }

    private static Label monoValue(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web(TEXT_MAIN));
        l.setFont(Font.font("Monospaced", 11));
        l.setWrapText(true);
        l.setMaxWidth(380);
        return l;
    }

    private static HBox buildCredentialsRow() {
        Label lbl = new Label("Professional registry, diplomas and ORCID are managed on the portal.");
        lbl.setTextFill(Color.web(TEXT_MUTED));
        lbl.setFont(Font.font("System", 11));
        lbl.setWrapText(true);

        Button btn = flatButton("🎓 Manage my credentials →", BLUE);
        btn.setOnAction(e -> BrowserOpener.open(PORTAL_URL));

        HBox row = new HBox(12, lbl, btn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(4, 20, 8, 20));
        return row;
    }

    /** What the image-copy settings mean for the user — shown live under them. */
    static String snapshotConsequences(io.qtrace.draft.SnapshotPolicy p) {
        StringBuilder sb = new StringBuilder();
        sb.append(QTraceI18n.t("settings.snapshot.c.capture")).append("\n\n");
        sb.append("• ").append(QTraceI18n.f("settings.snapshot.c.small", p.thresholdMb())).append("\n");
        sb.append("• ").append(p.largeKeyStepsOnly()
            ? QTraceI18n.f("settings.snapshot.c.largeKeyOnly", p.thresholdMb())
            : QTraceI18n.f("settings.snapshot.c.largeInterval", p.thresholdMb(), p.intervalS())).append("\n\n");
        sb.append(QTraceI18n.t("settings.snapshot.c.disk"));
        return sb.toString();
    }

    private static Label subTitle(String text) {
        Label lbl = new Label(text.toUpperCase());
        lbl.setTextFill(Color.web(TEXT_MUTED));
        lbl.setFont(Font.font("System", FontWeight.BOLD, 10));
        lbl.setStyle("-fx-letter-spacing: 0.6;");
        VBox.setMargin(lbl, new Insets(6, 0, -2, 0));
        return lbl;
    }

    private static Tooltip hintTooltip(String text) {
        Tooltip tip = new Tooltip(text);
        tip.setWrapText(true);
        tip.setMaxWidth(360);
        tip.setShowDelay(javafx.util.Duration.millis(200));
        return tip;
    }

    private static void showError(Stage owner, String msg) {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.ERROR);
        a.setTitle("qTrace Settings");
        a.setHeaderText(null);
        a.setContentText(msg);
        a.initOwner(owner);
        a.showAndWait();
    }

    private static void updateLicenseStatus(Label statusLbl, String path, TextField tfValidator, TextField tfEmail) {
        // Unlocked by default — only a verified, non-expired license re-locks it below.
        // Otherwise a stamp could be signed under someone else's certified name.
        tfValidator.setEditable(true);
        tfValidator.setDisable(false);
        tfValidator.setTooltip(null);
        tfEmail.setText("");

        if (path == null || path.isBlank()) {
            statusLbl.setText("No certificate loaded.");
            statusLbl.setTextFill(Color.web(TEXT_MUTED));
            return;
        }
        try {
            QTracePlugin plugin = QTracePluginManager.get();
            if (plugin == null) {
                statusLbl.setText("Compliance plugin not installed.");
                statusLbl.setTextFill(Color.web(ORANGE));
                return;
            }
            String token = java.nio.file.Files.readString(java.nio.file.Path.of(path)).strip();
            io.qtrace.LicenseInfo info = plugin.validateLicense(token);
            if (info == null) {
                statusLbl.setText("Invalid or corrupted certificate file.");
                statusLbl.setTextFill(Color.web(RED));
                return;
            }
            if (info.expired()) {
                statusLbl.setText("Certificate expired — renew it from " + PORTAL_URL);
                statusLbl.setTextFill(Color.web(ORANGE));
                return;
            }
            if (info.verified()) {
            statusLbl.setText("✓ Verified — " + info.name() + " · " + info.institution()
                + " · valid until " + info.expiresAtFormatted());
            statusLbl.setTextFill(Color.web(GREEN));
        } else {
            // Provisional certificate (loader.md § 17): replaced by the verified one automatically.
            statusLbl.setText("⏳ Identity verification pending — " + info.name()
                + " · provisional until " + info.expiresAtFormatted()
                + ". Finish the identity check on qtrace.ca: the certificate updates by itself.");
            statusLbl.setTextFill(Color.web(ORANGE));
        }
            // Certified identity — bound to the license, not freely editable.
            tfValidator.setText(info.name());
            tfValidator.setEditable(false);
            tfValidator.setDisable(true);
            tfValidator.setTooltip(new javafx.scene.control.Tooltip(
                "Locked — identity certified by your qTrace identity certificate."));
            tfEmail.setText(info.email() != null && !info.email().isBlank()
                ? info.email() : "(older certificate, regenerate it to include your email)");
        } catch (Exception ex) {
            statusLbl.setText("Could not read the certificate file.");
            statusLbl.setTextFill(Color.web(RED));
        }
    }
}
