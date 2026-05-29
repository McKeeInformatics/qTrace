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
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;

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

    public static void show(Stage owner) {
        Stage dlg = new Stage();
        dlg.initOwner(owner);
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle("QTrace — Settings");
        dlg.setResizable(false);

        QTraceConfig cfg = QTraceConfig.get();

        // ── Path rows ──────────────────────────────────────────────────────────
        TextField tfMetaScript  = pathField(cfg.rawMetaScriptDir());
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

        addRow(grid, 0, "Meta-Scripts (.groovy)",     tfMetaScript,  dlg);
        addRow(grid, 1, ".qtrace + CSV export",        tfExport,      dlg);
        addRow(grid, 2, "Classifier Git tracking",     tfClassifier,  dlg);
        addRow(grid, 3, "Training GeoJSON",            tfTraining,    dlg);

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

        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: " + BORDER + ";");

        // ── Buttons ────────────────────────────────────────────────────────────
        Button btnReset  = flatButton("Reset all to default", TEXT_MUTED);
        Button btnCancel = flatButton("Cancel",               TEXT_SUB);
        Button btnOk     = solidButton("Save",                BLUE);

        btnReset.setOnAction(e -> {
            tfMetaScript.clear();
            tfExport.clear();
            tfClassifier.clear();
            tfTraining.clear();
            tfValidator.clear();
        });

        btnCancel.setOnAction(e -> dlg.close());

        btnOk.setOnAction(e -> {
            cfg.setMetaScriptDir(tfMetaScript.getText());
            cfg.setExportDir(tfExport.getText());
            cfg.setClassifierDir(tfClassifier.getText());
            cfg.setTrainingDir(tfTraining.getText());
            cfg.setValidatorName(tfValidator.getText());
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
}
