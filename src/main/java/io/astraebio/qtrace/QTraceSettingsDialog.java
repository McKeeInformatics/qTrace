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
            try { Desktop.getDesktop().browse(new URI(PORTAL_URL)); }
            catch (Exception ignored) {}
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

        Separator sep3 = new Separator();
        sep3.setStyle("-fx-background-color: " + BORDER + ";");

        // ── PIN protection (Enterprise only) ───────────────────────────────────
        Label pinStatusLbl = new Label();
        pinStatusLbl.setFont(Font.font("System", 11));
        pinStatusLbl.setWrapText(true);
        pinStatusLbl.setMaxWidth(440);
        refreshPinStatus(pinStatusLbl, cfg);

        Button btnSetPin    = flatButton("Set PIN…",    BLUE);
        Button btnChangePin = flatButton("Change PIN…", TEXT_SUB);
        Button btnRemovePin = flatButton("Remove PIN",  ORANGE);

        btnSetPin.setVisible(!cfg.hasPinSet());
        btnSetPin.setManaged(!cfg.hasPinSet());
        btnChangePin.setVisible(cfg.hasPinSet());
        btnChangePin.setManaged(cfg.hasPinSet());
        btnRemovePin.setVisible(cfg.hasPinSet());
        btnRemovePin.setManaged(cfg.hasPinSet());

        btnSetPin.setOnAction(e -> {
            String pin = askPin(dlg, "Set license PIN", "Choose a PIN to protect your license:");
            if (pin == null) return;
            String confirm = askPin(dlg, "Confirm PIN", "Confirm your PIN:");
            if (confirm == null || !confirm.equals(pin)) {
                showError(dlg, "PINs do not match.");
                return;
            }
            cfg.setPinHash(sha256(pin));
            cfg.save();
            refreshPinStatus(pinStatusLbl, cfg);
            btnSetPin.setVisible(false);    btnSetPin.setManaged(false);
            btnChangePin.setVisible(true);  btnChangePin.setManaged(true);
            btnRemovePin.setVisible(true);  btnRemovePin.setManaged(true);
        });

        btnChangePin.setOnAction(e -> {
            String old = askPin(dlg, "Change PIN", "Current PIN:");
            if (old == null) return;
            if (!sha256(old).equals(cfg.getPinHash())) {
                showError(dlg, "Incorrect PIN.");
                return;
            }
            String pin = askPin(dlg, "New PIN", "New PIN:");
            if (pin == null) return;
            String confirm = askPin(dlg, "Confirm new PIN", "Confirm new PIN:");
            if (confirm == null || !confirm.equals(pin)) {
                showError(dlg, "PINs do not match.");
                return;
            }
            cfg.setPinHash(sha256(pin));
            cfg.save();
            refreshPinStatus(pinStatusLbl, cfg);
        });

        btnRemovePin.setOnAction(e -> {
            String pin = askPin(dlg, "Remove PIN", "Enter current PIN to confirm removal:");
            if (pin == null) return;
            if (!sha256(pin).equals(cfg.getPinHash())) {
                showError(dlg, "Incorrect PIN.");
                return;
            }
            cfg.setPinHash(null);
            cfg.save();
            refreshPinStatus(pinStatusLbl, cfg);
            btnSetPin.setVisible(true);    btnSetPin.setManaged(true);
            btnChangePin.setVisible(false); btnChangePin.setManaged(false);
            btnRemovePin.setVisible(false); btnRemovePin.setManaged(false);
        });

        HBox pinButtons = new HBox(8, btnSetPin, btnChangePin, btnRemovePin);
        pinButtons.setAlignment(Pos.CENTER_LEFT);
        pinButtons.setPadding(new Insets(4, 20, 4, 20));

        Label pinHint = new Label(
            "If set, a PIN will be required before every validation stamp. " +
            "Protects your license if your machine is compromised.");
        pinHint.setTextFill(Color.web(TEXT_MUTED));
        pinHint.setFont(Font.font("System", 10));
        pinHint.setWrapText(true);
        pinHint.setMaxWidth(440);

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
            sectionTitle("License PIN"),
            pinButtons, pinStatusLbl, pinHint,
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

    private static String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void refreshPinStatus(Label lbl, QTraceConfig cfg) {
        if (cfg.hasPinSet()) {
            lbl.setText("PIN is set — required before every validation stamp.");
            lbl.setTextFill(Color.web(GREEN));
        } else {
            lbl.setText("No PIN set — anyone with the license file can stamp.");
            lbl.setTextFill(Color.web(TEXT_MUTED));
        }
        lbl.setPadding(new Insets(0, 20, 4, 20));
    }

    private static String askPin(Stage owner, String title, String prompt) {
        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog();
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(prompt);
        d.initOwner(owner);
        // Style the dialog to match the dark theme as much as possible
        return d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
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
