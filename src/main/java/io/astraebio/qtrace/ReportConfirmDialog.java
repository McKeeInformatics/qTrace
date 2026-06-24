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
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.util.StringConverter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Pre-send confirmation for the activity report: shows the user the exact digest
 * that would leave their machine (audit/transparency) before anything is sent to
 * the report endpoint. Enterprise-only flow.
 *
 * Returns a {@link Result}: whether to proceed, and whether to stop asking
 * (re-enableable in Settings → Security).
 */
public class ReportConfirmDialog {

    private static final String BG_BASE   = "#1e1e2e";
    private static final String BG_CARD   = "#24273a";
    private static final String BORDER    = "#313244";
    private static final String TEXT_MAIN = "#cdd6f4";
    private static final String TEXT_SUB  = "#a6adc8";
    private static final String TEXT_MUTED= "#6c7086";
    private static final String BLUE      = "#89b4fa";
    private static final String GREEN     = "#a6e3a1";

    public static final class Result {
        public boolean send = false;
        public boolean dontAskAgain = false;
        public String  lang = null;   // chosen report language code
    }

    /** Modal — blocks until the user chooses. */
    public static Result show(Window owner, String digestPretty) {
        Result result = new Result();

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(QTraceI18n.t("report.confirm.title"));

        Label notice = new Label(QTraceI18n.t("report.confirm.notice"));
        notice.setWrapText(true);
        notice.setTextFill(javafx.scene.paint.Color.web(TEXT_SUB));

        TextArea area = new TextArea(digestPretty);
        area.setEditable(false);
        area.setWrapText(true);
        area.setFont(Font.font("Monospaced", 12));
        area.setStyle(
            "-fx-control-inner-background: " + BG_CARD + ";"
          + "-fx-text-fill: " + TEXT_MAIN + ";"
          + "-fx-border-color: " + BORDER + ";");
        VBox.setVgrow(area, Priority.ALWAYS);

        Label langLabel = new Label(QTraceI18n.t("report.confirm.language"));
        langLabel.setTextFill(javafx.scene.paint.Color.web(TEXT_SUB));
        ComboBox<String> langBox = new ComboBox<>();
        for (String[] l : ReportLanguages.LANGS) langBox.getItems().add(l[0]);
        langBox.setConverter(new StringConverter<>() {
            @Override public String toString(String code) { return code == null ? "" : ReportLanguages.label(code); }
            @Override public String fromString(String s) { return s; }
        });
        langBox.setValue(QTraceConfig.get().getReportLanguage());
        HBox langRow = new HBox(8, langLabel, langBox);
        langRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox pseudonymize = new CheckBox(QTraceI18n.t("report.confirm.pseudonymize"));
        pseudonymize.setDisable(true);   // shown now, implemented later
        pseudonymize.setTextFill(javafx.scene.paint.Color.web(TEXT_MUTED));
        Label soon = new Label(QTraceI18n.t("report.confirm.soon"));
        soon.setTextFill(javafx.scene.paint.Color.web(TEXT_MUTED));
        soon.setFont(Font.font("System", 10));
        HBox pseudoRow = new HBox(8, pseudonymize, soon);
        pseudoRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox dontAsk = new CheckBox(QTraceI18n.t("report.confirm.dontask"));
        dontAsk.setTextFill(javafx.scene.paint.Color.web(TEXT_SUB));

        Button cancel = new Button(QTraceI18n.t("report.confirm.cancel"));
        cancel.setStyle(buttonStyle(TEXT_SUB));
        cancel.setOnAction(e -> { result.send = false; stage.close(); });

        Button send = new Button(QTraceI18n.t("report.confirm.send"));
        send.setStyle(buttonStyle(GREEN));
        send.setOnAction(e -> {
            result.send = true;
            result.dontAskAgain = dontAsk.isSelected();
            result.lang = langBox.getValue();
            stage.close();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, send);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, notice, area, langRow, pseudoRow, dontAsk, buttons);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: " + BG_BASE + ";");

        stage.setScene(new Scene(root, 720, 600));
        stage.showAndWait();
        return result;
    }

    private static String buttonStyle(String textColor) {
        return "-fx-background-color: " + BG_CARD + ";"
             + "-fx-text-fill: " + textColor + ";"
             + "-fx-border-color: " + BORDER + ";"
             + "-fx-border-radius: 4; -fx-background-radius: 4;"
             + "-fx-padding: 4 12 4 12;";
    }
}
