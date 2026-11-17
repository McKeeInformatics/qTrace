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
 */

package io.qtrace.player;

import io.qtrace.DialogLook;
import io.qtrace.LicenseInfo;
import io.qtrace.QTraceConfig;
import io.qtrace.QTracePlugin;
import io.qtrace.QTracePluginManager;
import io.qtrace.Theme;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;

import java.util.Optional;

/**
 * The validation a batch of two or more Targets gives each image it plays, asked once before
 * the batch starts: the same four fields as Import's batch, the same stamp. With a certified
 * identity the validator is that identity, not editable.
 */
final class BatchStampDialog {

    /** What every image of the batch is stamped with. */
    record BatchStamp(String validator, String scope, String confidence, String notes) {}

    static final java.util.List<String> SCOPES = java.util.List.of(
        "Full Workflow", "Image QC", "Segmentation", "Feature Extraction", "Phenotyping", "Spatial Analysis");
    static final java.util.List<String> CONFIDENCES = java.util.List.of("High", "Medium", "Low");

    private static final String FIELD =
        "-fx-background-color: " + Theme.v("field") + "; -fx-control-inner-background: " + Theme.v("field") + ";"
      + "-fx-text-fill: " + DialogLook.TEXT_MAIN + "; -fx-prompt-text-fill: -qt-text-muted;"
      + "-fx-border-color: -qt-border; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12;";
    private static final String COMBO =
        "-fx-background-color: " + DialogLook.BG_SURFACE + "; -fx-border-color: -qt-border;"
      + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12;";

    private BatchStampDialog() {}

    /** Empty when the user cancels: the batch then does not start. FX thread. */
    static Optional<BatchStamp> ask(Window owner, int targetCount, String source) {
        QTracePlugin entitled = QTracePluginManager.getEntitled();
        LicenseInfo license = entitled != null ? entitled.getActiveLicenseInfo() : null;

        TextField validatorFld = new TextField(
            license != null ? license.name() : QTraceConfig.get().getValidatorName());
        validatorFld.setPromptText("Dr. Lastname / Analyst ID");
        validatorFld.setPrefWidth(280);
        validatorFld.setPrefHeight(30);
        validatorFld.setStyle(FIELD);
        Node validatorNode = license != null ? certifiedBadge(license) : validatorFld;

        ComboBox<String> scopeBox = new ComboBox<>(FXCollections.observableArrayList(SCOPES));
        scopeBox.setValue(SCOPES.get(0));
        scopeBox.setPrefHeight(30);
        scopeBox.setStyle(COMBO);

        ComboBox<String> confidenceBox = new ComboBox<>(FXCollections.observableArrayList(CONFIDENCES));
        confidenceBox.setValue(CONFIDENCES.get(0));
        confidenceBox.setPrefHeight(30);
        confidenceBox.setStyle(COMBO);

        TextArea notesFld = new TextArea();
        notesFld.setPromptText("Optional - applies to every image of this batch");
        notesFld.setPrefRowCount(2);
        notesFld.setPrefWidth(280);
        notesFld.setWrapText(true);
        notesFld.setStyle(FIELD);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(18, 20, 6, 20));
        int r = 0;
        grid.add(label("Validator *"), 0, r); grid.add(validatorNode, 1, r++);
        grid.add(label("Scope"), 0, r);       grid.add(scopeBox, 1, r++);
        grid.add(label("Confidence"), 0, r);  grid.add(confidenceBox, 1, r++);
        grid.add(label("Notes"), 0, r);       grid.add(notesFld, 1, r);

        Dialog<BatchStamp> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("qTrace Player - Stamp each image");
        ButtonType start = new ButtonType("Start batch", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(start, ButtonType.CANCEL);
        DialogLook.apply(dlg, "Stamp each image - " + targetCount + " Targets",
            "Each image is stamped once the instructions of " + source + " have run on it without error."
                + " An image where a step failed keeps an unstamped .qtrace.",
            grid, start, DialogLook.BLUE, ButtonType.CANCEL);

        Button startBtn = (Button) dlg.getDialogPane().lookupButton(start);
        startBtn.setDisable(validatorFld.getText().trim().isEmpty());
        validatorFld.textProperty().addListener((obs, o, n) -> startBtn.setDisable(n == null || n.trim().isEmpty()));

        dlg.setResultConverter(btn -> btn != start ? null : new BatchStamp(
            validatorFld.getText().trim(), scopeBox.getValue(), confidenceBox.getValue(), notesFld.getText().trim()));
        return dlg.showAndWait();
    }

    private static Label label(String text) {
        Label l = new Label(text);
        Theme.text(l, DialogLook.TEXT_SUB);
        l.setFont(Font.font("System", 12));
        return l;
    }

    /** The certified identity in place of the Validator field: gold, not editable (as in Import). */
    private static Node certifiedBadge(LicenseInfo license) {
        Label check = new Label("✓");
        check.setFont(Font.font("System", FontWeight.BOLD, 13));
        check.setStyle("-fx-text-fill: -qt-gold;");
        Label name = new Label(license.name());
        name.setFont(Font.font("System", FontWeight.BOLD, 12));
        name.setStyle("-fx-text-fill: " + DialogLook.TEXT_MAIN + ";");
        Label cert = new Label(license.institution() + " · valid until " + license.expiresAtFormatted());
        cert.setFont(Font.font("System", 10));
        cert.setStyle("-fx-text-fill: -qt-gold;");
        HBox badge = new HBox(8, check, new VBox(1, name, cert));
        badge.setAlignment(Pos.CENTER_LEFT);
        badge.setPadding(new Insets(6, 12, 6, 12));
        badge.setPrefWidth(280);
        badge.setStyle("-fx-background-color: -qt-gold-soft; -fx-background-radius: 6;"
            + "-fx-border-color: -qt-gold-line; -fx-border-radius: 6;");
        return badge;
    }
}
