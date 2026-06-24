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

package io.astraebio.qtrace;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

import java.awt.Desktop;
import java.io.File;
import java.net.URI;

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

    public static void show(Stage owner) {
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

        GridPane grid = new GridPane();
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

        // Hint
        Label hint = new Label("Leave blank to use default: " + QTraceConfig.defaultDirString());
        hint.setTextFill(Color.web(TEXT_MUTED));
        hint.setFont(Font.font("System", 10));
        hint.setWrapText(true);
        hint.setMaxWidth(440);

        // ── Validator section ──────────────────────────────────────────────────
        TextField tfValidator = new TextField(cfg.getValidatorName());
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

        Label validatorLbl = new Label("Nom du validateur");
        validatorLbl.setTextFill(Color.web(TEXT_SUB));
        validatorLbl.setFont(Font.font("System", FontWeight.NORMAL, 12));
        validatorGrid.add(validatorLbl, 0, 0);
        validatorGrid.add(tfValidator,  1, 0);

        Label validatorHint = new Label(
            "Si renseigné, pré-remplit le champ \"Validator\" dans la popup Validate & Stamp (modifiable à tout moment).");
        validatorHint.setTextFill(Color.web(TEXT_MUTED));
        validatorHint.setFont(Font.font("System", 10));
        validatorHint.setWrapText(true);
        validatorHint.setMaxWidth(440);

        // ── Enterprise License section ─────────────────────────────────────────
        TextField tfLicense = new TextField(cfg.getLicensePath());
        tfLicense.setPromptText("(no license loaded)");
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
        updateLicenseStatus(licenseStatusLbl, cfg.getLicensePath(), tfValidator);

        Button btnBrowseLicense = flatButton("Browse…", TEXT_MUTED);
        btnBrowseLicense.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select .qtlicense file");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("qTrace License", "*.qtlicense"));
            String current = tfLicense.getText().strip();
            if (!current.isEmpty()) {
                File f = new File(current);
                if (f.getParentFile() != null && f.getParentFile().isDirectory())
                    fc.setInitialDirectory(f.getParentFile());
            }
            File chosen = fc.showOpenDialog(dlg);
            if (chosen != null) {
                tfLicense.setText(chosen.getAbsolutePath());
                updateLicenseStatus(licenseStatusLbl, chosen.getAbsolutePath(), tfValidator);
            }
        });

        Button btnGetLicense = flatButton("🔗 Get license", BLUE);
        btnGetLicense.setOnAction(e -> {
            new Thread(() -> {
                try { Desktop.getDesktop().browse(new URI(PORTAL_URL)); }
                catch (Exception ignored) {}
            }, "qtrace-browser").start();
        });

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

        Label licenseLbl = new Label(".qtlicense file");
        licenseLbl.setTextFill(Color.web(TEXT_SUB));
        licenseLbl.setFont(Font.font("System", FontWeight.NORMAL, 12));
        licenseGrid.add(licenseLbl,      0, 0);
        licenseGrid.add(tfLicense,       1, 0);
        licenseGrid.add(btnBrowseLicense,2, 0);
        licenseGrid.add(btnGetLicense,   3, 0);
        licenseGrid.add(licenseStatusLbl, 1, 1, 3, 1);

        Separator sep2 = new Separator();
        sep2.setStyle("-fx-background-color: " + BORDER + ";");

        Separator sep  = new Separator();
        sep.setStyle("-fx-background-color: " + BORDER + ";");

        // ── Security (activity report) ──────────────────────────────────────────
        Separator sep3 = new Separator();
        sep3.setStyle("-fx-background-color: " + BORDER + ";");

        CheckBox chkReportConfirm = new CheckBox(QTraceI18n.t("settings.security.confirm"));
        chkReportConfirm.setSelected(cfg.isReportConfirmBeforeSend());
        chkReportConfirm.setTextFill(Color.web(TEXT_SUB));
        chkReportConfirm.setWrapText(true);

        Label securityHint = new Label(QTraceI18n.t("settings.security.confirm.hint"));
        securityHint.setTextFill(Color.web(TEXT_MUTED));
        securityHint.setFont(Font.font("System", 11));
        securityHint.setWrapText(true);

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

        VBox securityBox = new VBox(6, chkReportConfirm, securityHint, langRow, pseudoRow);
        securityBox.setPadding(new Insets(4, 20, 8, 20));

        // ── Buttons ────────────────────────────────────────────────────────────
        Button btnReset  = flatButton("Reset all to default", TEXT_MUTED);
        Button btnCancel = flatButton("Cancel",               TEXT_SUB);
        Button btnOk     = solidButton("Save",                BLUE);

        btnReset.setOnAction(e -> {
            tfExport.clear();
            tfClassifier.clear();
            tfTraining.clear();
            tfValidator.clear();
            tfLicense.clear();
            updateLicenseStatus(licenseStatusLbl, "", tfValidator);
        });

        btnCancel.setOnAction(e -> dlg.close());

        btnOk.setOnAction(e -> {
            cfg.setExportDir(tfExport.getText());
            cfg.setClassifierDir(tfClassifier.getText());
            cfg.setTrainingDir(tfTraining.getText());
            cfg.setValidatorName(tfValidator.getText());
            cfg.setLicensePath(tfLicense.getText());
            cfg.setReportConfirmBeforeSend(chkReportConfirm.isSelected());
            if (langBox.getValue() != null) cfg.setReportLanguage(langBox.getValue());
            cfg.save();
            dlg.close();
        });

        HBox buttonRow = new HBox(8, btnReset, spacer(), btnCancel, btnOk);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(8, 20, 16, 20));

        // ── Root ───────────────────────────────────────────────────────────────
        VBox root = new VBox(0,
            sectionTitle("Export Path Configuration"),
            grid, hint,
            sep,
            sectionTitle("Validator"),
            validatorGrid, validatorHint,
            sep2,
            sectionTitle("Enterprise License"),
            licenseGrid,
            sep3,
            sectionTitle(QTraceI18n.t("settings.security.tab")),
            securityBox,
            buttonRow);
        VBox.setMargin(hint,          new Insets(0, 20, 8, 20));
        VBox.setMargin(validatorHint, new Insets(0, 20, 8, 20));
        root.setStyle("-fx-background-color: " + BG_BASE + ";");
        root.setPrefWidth(520);

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

    private static Label sectionTitle(String text) {
        Label lbl = new Label(text);
        lbl.setTextFill(Color.web(TEXT_MAIN));
        lbl.setFont(Font.font("System", FontWeight.BOLD, 13));
        lbl.setPadding(new Insets(14, 20, 4, 20));
        return lbl;
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

    private static void showError(Stage owner, String msg) {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.ERROR);
        a.setTitle("qTrace Settings");
        a.setHeaderText(null);
        a.setContentText(msg);
        a.initOwner(owner);
        a.showAndWait();
    }

    private static void updateLicenseStatus(Label statusLbl, String path, TextField tfValidator) {
        if (path == null || path.isBlank()) {
            statusLbl.setText("No license loaded.");
            statusLbl.setTextFill(Color.web(TEXT_MUTED));
            return;
        }
        try {
            QTracePlugin enterprise = QTracePluginManager.get();
            if (enterprise == null) {
                statusLbl.setText("Enterprise plugin not installed.");
                statusLbl.setTextFill(Color.web(ORANGE));
                return;
            }
            String token = java.nio.file.Files.readString(java.nio.file.Path.of(path)).strip();
            io.astraebio.qtrace.LicenseInfo info = enterprise.validateLicense(token);
            if (info == null) {
                statusLbl.setText("Invalid or corrupted license file.");
                statusLbl.setTextFill(Color.web(RED));
                return;
            }
            if (info.expired()) {
                statusLbl.setText("License expired — download a new one from " + PORTAL_URL);
                statusLbl.setTextFill(Color.web(ORANGE));
                return;
            }
            statusLbl.setText("✓ Verified — " + info.name() + " · " + info.institution()
                + " · valid until " + info.expiresAtFormatted());
            statusLbl.setTextFill(Color.web(GREEN));
            if (tfValidator.getText().isBlank()) tfValidator.setText(info.name());
        } catch (Exception ex) {
            statusLbl.setText("Could not read license file.");
            statusLbl.setTextFill(Color.web(RED));
        }
    }
}
