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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Asked after "File > Import objects from file" has picked its file and before QuPath reads it:
 * the context of the file (mandatory) and the scripts that generated it (optional, several).
 * Cancel (or Esc) cancels the import itself.
 */
public final class ImportContextDialog {

    private ImportContextDialog() {}

    public static Optional<ImportContext> show(Window owner, File imported) {
        Dialog<ImportContext> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);

        ButtonType ok = new ButtonType(QTraceI18n.t("import.context.ok"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE); // Esc
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);

        Label intro = new Label(QTraceI18n.t("import.context.intro"));
        intro.setWrapText(true);
        intro.setStyle("-fx-text-fill: " + DialogLook.TEXT_SUB + "; -fx-font-size: 11;");

        Label fieldLbl = new Label(QTraceI18n.t("import.context.field"));
        fieldLbl.setStyle("-fx-text-fill: " + DialogLook.TEXT_MAIN + "; -fx-font-size: 12; -fx-font-weight: bold;");
        TextArea context = new TextArea();
        context.setPromptText(QTraceI18n.t("import.context.prompt"));
        context.setWrapText(true);
        context.setPrefRowCount(4);

        Label scriptsLbl = new Label(QTraceI18n.t("import.context.scripts"));
        scriptsLbl.setStyle(fieldLbl.getStyle());
        Label scriptsHint = new Label(QTraceI18n.t("import.context.scripts.hint"));
        scriptsHint.setWrapText(true);
        scriptsHint.setStyle(intro.getStyle());

        ListView<File> scripts = new ListView<>();
        scripts.setPrefHeight(96);
        scripts.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(File f, boolean empty) {
                super.updateItem(f, empty);
                setText(empty || f == null ? null : f.getName());
                setTooltip(empty || f == null ? null : new javafx.scene.control.Tooltip(f.getAbsolutePath()));
            }
        });

        Button add = new Button(QTraceI18n.t("import.context.add"));
        Button remove = new Button(QTraceI18n.t("import.context.remove"));
        remove.disableProperty().bind(scripts.getSelectionModel().selectedItemProperty().isNull());
        add.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(QTraceI18n.t("import.context.choose"));
            if (imported.getParentFile() != null) chooser.setInitialDirectory(imported.getParentFile());
            List<File> chosen = chooser.showOpenMultipleDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen == null) return;
            for (File f : chosen) if (!scripts.getItems().contains(f)) scripts.getItems().add(f);
        });
        remove.setOnAction(e -> scripts.getItems().remove(scripts.getSelectionModel().getSelectedItem()));

        VBox body = new VBox(6, intro, fieldLbl, context, scriptsLbl, scriptsHint, scripts, new HBox(8, add, remove));
        body.setPadding(new Insets(14, 20, 8, 20));
        body.setPrefWidth(460);
        DialogLook.apply(dialog, QTraceI18n.t("import.context.title"),
            QTraceI18n.f("import.context.subtitle", imported.getName()), (Node) body, ok, DialogLook.BLUE, cancel);

        dialog.getDialogPane().lookupButton(ok).disableProperty().bind(
            javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !ImportContext.isAcceptable(context.getText()), context.textProperty()));
        dialog.setResultConverter(b -> b == ok
            ? new ImportContext(context.getText(), new ArrayList<>(scripts.getItems())) : null);
        javafx.application.Platform.runLater(context::requestFocus);
        return dialog.showAndWait();
    }
}
