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
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Modal field-selection dialog for the Dashboard's CSV export — everything checked by default. */
final class ExportFieldsDialog {

    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_CARD    = "#24273a";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String BLUE       = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";

    static final class Result {
        boolean confirmed = false;
        Set<String> selectedFieldIds = new HashSet<>();
        boolean includeByClass = false;
    }

    private ExportFieldsDialog() {}

    /** Modal — blocks until the user chooses. {@code dashboardFields} and {@code imageFields} are exportable field ids/labels. */
    static Result show(Window owner, List<String[]> dashboardFields, List<String[]> imageFields) {
        Result result = new Result();

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(QTraceI18n.t("export.fields.title"));

        List<CheckBox> allBoxes = new ArrayList<>();

        VBox dashboardBox = new VBox(4);
        for (String[] f : dashboardFields) allBoxes.add(fieldCheckBox(dashboardBox, f[0], f[1]));

        VBox imageBox = new VBox(4);
        for (String[] f : imageFields) allBoxes.add(fieldCheckBox(imageBox, f[0], f[1]));

        CheckBox byClass = new CheckBox(QTraceI18n.t("export.fields.byclass"));
        byClass.setSelected(true);
        byClass.setTextFill(Color.web(TEXT_SUB));

        Hyperlink selectAll = new Hyperlink(QTraceI18n.t("export.fields.selectall"));
        Hyperlink selectNone = new Hyperlink(QTraceI18n.t("export.fields.selectnone"));
        selectAll.setOnAction(e -> { for (CheckBox cb : allBoxes) cb.setSelected(true); byClass.setSelected(true); });
        selectNone.setOnAction(e -> { for (CheckBox cb : allBoxes) cb.setSelected(false); byClass.setSelected(false); });
        HBox selectRow = new HBox(12, selectAll, selectNone);

        VBox root = new VBox(10,
            selectRow,
            sectionLabel(QTraceI18n.t("export.fields.section.dashboard")), dashboardBox,
            sectionLabel(QTraceI18n.t("export.fields.section.image")),     imageBox,
            byClass
        );
        root.setPadding(new Insets(4));

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:" + BG_BASE + ";-fx-background:" + BG_BASE + ";");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button cancel = new Button(QTraceI18n.t("export.fields.cancel"));
        cancel.setStyle(buttonStyle(TEXT_SUB));
        cancel.setOnAction(e -> { result.confirmed = false; stage.close(); });

        Button export = new Button(QTraceI18n.t("export.fields.export"));
        export.setStyle(buttonStyle(GREEN));
        export.setOnAction(e -> {
            result.confirmed = true;
            for (CheckBox cb : allBoxes)
                if (cb.isSelected()) result.selectedFieldIds.add((String) cb.getUserData());
            result.includeByClass = byClass.isSelected();
            stage.close();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, export);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox outer = new VBox(10, scroll, buttons);
        outer.setPadding(new Insets(12));
        outer.setStyle("-fx-background-color:" + BG_BASE + ";");

        stage.setScene(new Scene(outer, 420, 560));
        stage.showAndWait();
        return result;
    }

    private static CheckBox fieldCheckBox(VBox container, String id, String label) {
        CheckBox cb = new CheckBox(label);
        cb.setUserData(id);
        cb.setSelected(true);
        cb.setTextFill(Color.web(TEXT_SUB));
        container.getChildren().add(cb);
        return cb;
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web(BLUE));
        l.setFont(Font.font("System", FontWeight.BOLD, 11));
        return l;
    }

    private static String buttonStyle(String textColor) {
        return "-fx-background-color: " + BG_CARD + ";"
             + "-fx-text-fill: " + textColor + ";"
             + "-fx-border-color: " + BORDER + ";"
             + "-fx-border-radius: 4; -fx-background-radius: 4;"
             + "-fx-padding: 4 12 4 12;";
    }
}
