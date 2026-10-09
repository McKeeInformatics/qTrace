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

package io.qtrace.welcome;

import io.qtrace.BrowserOpener;
import io.qtrace.IssueReportDialog;
import io.qtrace.QTraceController;
import io.qtrace.QTracePlugin;
import io.qtrace.QTracePluginManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

import java.util.List;

/** Plain fallback welcome (no WebView): header (organization logo + title) and steps of a {@link WelcomeSpec}. */
final class WelcomeDialog {

    /** Actions this version can run — sent to the server as ?v= (web/lib/onboarding.ts ACTIONS_SINCE). */
    static final List<String> ACTIONS = List.of("unlock-key", "open-url", "open-player", "issue-report");

    private final QuPathGUI qupath;
    private final Stage stage = new Stage();

    WelcomeDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        if (qupath != null && qupath.getStage() != null) stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle("Welcome to qTrace");
    }

    void show(WelcomeSpec w) {
        VBox root = new VBox(14);
        root.setPadding(new Insets(24));
        root.setPrefWidth(540);

        HBox header = new HBox(14);
        header.setAlignment(Pos.CENTER_LEFT);
        if (w.logoUrl() != null) {
            ImageView logo = new ImageView(new Image(w.logoUrl(), 0, 48, true, true, true));
            header.getChildren().add(logo);
        }
        VBox titles = new VBox(4);
        Label title = new Label(w.title());
        title.setWrapText(true);
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        titles.getChildren().add(title);
        if (w.subtitle() != null) titles.getChildren().add(muted(w.subtitle()));
        header.getChildren().add(titles);
        root.getChildren().add(header);

        if (!w.steps().isEmpty()) {
            VBox steps = new VBox(8);
            for (WelcomeSpec.Step s : w.steps()) {
                Label mark = new Label("○");
                Label label = new Label(s.label());
                label.setWrapText(true);
                Region grow = new Region();
                HBox.setHgrow(grow, Priority.ALWAYS);
                Button go = new Button(s.action().equals("open-url") ? "Open" : "Start");
                go.setOnAction(e -> {
                    run(s);
                    mark.setText("✓");
                });
                HBox row = new HBox(10, mark, label, grow, go);
                row.setAlignment(Pos.CENTER_LEFT);
                steps.getChildren().add(row);
            }
            root.getChildren().add(steps);
        }

        root.getChildren().add(muted("Reopen this window from Extensions > QTrace > Welcome…"));

        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());
        HBox bottom = new HBox(close);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(bottom);

        stage.setScene(new Scene(root));
        stage.show();
    }

    private void run(WelcomeSpec.Step s) {
        switch (s.action()) {
            case "unlock-key" -> {
                QTracePlugin p = QTracePluginManager.getEntitled();
                if (p == null) info("Compliance is not active yet. Restart QuPath, then try again.");
                else p.promptPassphraseAndDecrypt(stage);
            }
            case "open-url" -> { if (s.url() != null) BrowserOpener.open(s.url()); }
            case "open-player" -> {
                QTraceController c = QTraceController.current();
                if (c != null) c.openReplayDialog();
            }
            case "issue-report" -> IssueReportDialog.show(qupath);
            default -> { }
        }
    }

    private void info(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg);
        a.initOwner(stage);
        a.setHeaderText(null);
        a.show();
    }

    private static Label muted(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setStyle("-fx-opacity: 0.75;");
        return l;
    }
}
