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

package io.qtrace.player;

import io.qtrace.Theme;
import io.qtrace.QTraceController;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Modal dialog for replaying a .qtrace bundle from the qtrace.ca workspace.
 * The user pastes a qtc_... ID (or full URL) and clicks Run; the dialog
 * downloads the .qtrace and classifiers, generates a Groovy script, and
 * pre-loads it into QuPath's Script Editor.
 */
class QTraceReplayDialog {

    private static final String BG       = "-qt-bg-base";
    private static final String SURFACE  = "-qt-bg-surface";
    private static final String BORDER   = "-qt-border";
    private static final String TEXT     = "-qt-text-main";
    private static final String MUTED    = "-qt-text-muted";
    private static final String BLUE     = "-qt-blue";
    private static final String GREEN    = "-qt-green";
    private static final String RED      = "-qt-red";

    private final QuPathGUI qupath;
    private TextArea logArea;
    private Button runBtn;

    QTraceReplayDialog(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    void show() {
        Stage stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("qTrace — Replay from workspace");
        stage.setResizable(true);
        stage.setMinWidth(420);
        stage.setMinHeight(320);
        stage.setWidth(480);
        stage.setHeight(380);

        // ── Input field ──────────────────────────────────────────────────────
        Label inputLabel = label("qtc_ ID or workspace URL", TEXT, 11, FontWeight.NORMAL);

        TextField inputField = new TextField();
        inputField.setPromptText("qtc_01KV…  or  https://qtrace.ca/…");
        inputField.setStyle(
            "-fx-background-color: " + SURFACE + ";"
          + "-fx-text-fill: " + TEXT + ";"
          + "-fx-prompt-text-fill: " + MUTED + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4;"
          + "-fx-background-radius: 4;"
          + "-fx-padding: 6 8 6 8;"
        );

        // ── Run button ───────────────────────────────────────────────────────
        runBtn = new Button("▶   Run");
        runBtn.setMaxWidth(Double.MAX_VALUE);
        runBtn.setFont(Font.font("System", FontWeight.BOLD, 13));
        Theme.text(runBtn, TEXT);
        runBtn.setStyle(
            "-fx-background-color: " + BLUE + ";"
          + "-fx-background-radius: 4;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 7 0 7 0;"
        );
        runBtn.setOnMouseEntered(e -> runBtn.setStyle(
            "-fx-background-color: -qt-sky;"
          + "-fx-background-radius: 4;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 7 0 7 0;"
        ));
        runBtn.setOnMouseExited(e -> runBtn.setStyle(
            "-fx-background-color: " + BLUE + ";"
          + "-fx-background-radius: 4;"
          + "-fx-cursor: hand;"
          + "-fx-padding: 7 0 7 0;"
        ));
        runBtn.setOnAction(e -> runReplay(inputField.getText().strip(), stage));

        // Allow Enter key to trigger Run
        inputField.setOnAction(e -> runBtn.fire());

        // ── Activity log ─────────────────────────────────────────────────────
        Label logLabel = label("Activity log", MUTED, 10, FontWeight.NORMAL);

        logArea = new TextArea();
        logArea.setEditable(false);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        logArea.setStyle(
            "-fx-background-color: " + SURFACE + ";"
          + "-fx-text-fill: " + MUTED + ";"
          + "-fx-font-size: 10;"
          + "-fx-font-family: monospace;"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-control-inner-background: " + SURFACE + ";"
        );

        // ── Layout ───────────────────────────────────────────────────────────
        VBox root = new VBox(10,
            inputLabel,
            inputField,
            runBtn,
            logLabel,
            logArea
        );
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: " + BG + ";");

        stage.setScene(new Scene(root));
        stage.show();
    }

    private void runReplay(String input, Stage stage) {
        if (input.isBlank()) return;
        runBtn.setDisable(true);
        logArea.clear();

        new Thread(() -> {
            Path classifierDir = null;
            try {
                // 1. Download .qtrace
                log("⬇ Downloading .qtrace...");
                String qtraceJson = QTraceReplayEngine.downloadQtrace(input);
                log("  ✓ .qtrace received");

                // 2. Download classifiers
                String certId = QTraceReplayEngine.extractCertId(input);
                int classifierCount = 0;

                log("⬇ Downloading classifiers...");
                classifierDir = Files.createTempDirectory("qtrace_classifiers_");
                try {
                    QTraceReplayEngine.downloadClassifiers(certId, classifierDir);
                    List<Path> clfFiles = Files.list(classifierDir)
                        .filter(p -> p.toString().endsWith(".json"))
                        .collect(Collectors.toList());
                    classifierCount = clfFiles.size();
                    if (classifierCount == 0) {
                        log("  (no classifiers in this bundle)");
                    } else {
                        for (Path f : clfFiles) log("  · " + f.getFileName());
                    }
                } catch (Exception e) {
                    log("  (no classifiers: " + e.getMessage() + ")");
                    classifierDir = null;
                }

                // 3. Generate Groovy script
                log("⚙ Generating Groovy script...");
                Path clfDir = classifierDir;
                String script = QTraceReplayEngine.generateGroovyScript(qtraceJson, clfDir, -1);

                // Count scriptable lines as a proxy for step count
                long steps = script.lines()
                    .filter(l -> !l.startsWith("//") && !l.isBlank() && !l.startsWith("import"))
                    .count();
                final int capturedCount = classifierCount;
                log("✓ " + steps + " step(s) — " + capturedCount + " classifier(s) restored");
                // Write to temp file as fallback reference
                Path tmpScript = Files.createTempFile("qtrace_replay_", ".groovy");
                Files.writeString(tmpScript, script);
                log("  Saved to: " + tmpScript.getFileName());

                // 4. Show pre-flight checklist; Script Editor opens on user confirmation
                Platform.runLater(() -> {
                    runBtn.setDisable(false);
                    QTraceReplayEngine.PreflightInfo preflightInfo =
                        QTraceReplayEngine.extractPreflightInfo(qtraceJson);
                    new QTracePreflightDialog(qupath, preflightInfo, stage,
                        () -> QTraceController.openInScriptEditor(qupath, script)).show();
                });

            } catch (Exception e) {
                log("✗ Error: " + e.getMessage());
                Platform.runLater(() -> runBtn.setDisable(false));
            } finally {
                // Clean up temp classifier dir (content already inlined in script)
                if (classifierDir != null) {
                    Path dir = classifierDir;
                    try {
                        Files.walk(dir).sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                    } catch (IOException ignored) {}
                }
            }
        }, "qtrace-replay").start();
    }

    private void log(String msg) {
        Platform.runLater(() -> {
            logArea.appendText(msg + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private static Label label(String text, String color, int size, FontWeight weight) {
        Label l = new Label(text);
        Theme.text(l, color);
        l.setFont(Font.font("System", weight, size));
        return l;
    }
}
