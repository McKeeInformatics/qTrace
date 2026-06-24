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

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Read-only preview window for an Enterprise activity report (Markdown).
 * Phase 1: shows the raw Markdown in a monospace text area with a "Save (.md)"
 * button that writes next to the source .qtrace. A richer rendering / PDF export
 * is planned for phase 2.
 */
public class ReportDialog {

    private static final String BG_BASE   = "#1e1e2e";
    private static final String BG_CARD   = "#24273a";
    private static final String BORDER    = "#313244";
    private static final String TEXT_MAIN = "#cdd6f4";
    private static final String BLUE      = "#89b4fa";

    /**
     * @param owner    owner window (may be null)
     * @param qtrace   the source .qtrace path — used to derive the default save location
     * @param markdown the report text
     * @param log      callback for user-facing status lines (may be null)
     */
    public static void show(Window owner, Path qtrace, String markdown,
                             QTracePlugin ep, Consumer<String> log) {
        Stage stage = new Stage();
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.NONE);
        }
        stage.setTitle(QTraceI18n.t("report.window.title"));

        TextArea area = new TextArea(markdown);
        area.setEditable(false);
        area.setWrapText(true);
        area.setFont(Font.font("Monospaced", 12));
        area.setStyle(
            "-fx-control-inner-background: " + BG_CARD + ";"
          + "-fx-text-fill: " + TEXT_MAIN + ";"
          + "-fx-highlight-fill: " + BLUE + ";"
          + "-fx-border-color: " + BORDER + ";");
        VBox.setVgrow(area, Priority.ALWAYS);

        Button save = new Button(QTraceI18n.t("report.save"));
        save.setStyle(
            "-fx-background-color: " + BG_CARD + ";"
          + "-fx-text-fill: " + BLUE + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4;");
        save.setOnAction(e -> saveMarkdown(stage, qtrace, markdown, log));

        Button exportPdf = new Button(QTraceI18n.t("report.export.pdf"));
        exportPdf.setStyle(
            "-fx-background-color: " + BG_CARD + ";"
          + "-fx-text-fill: " + BLUE + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-radius: 4; -fx-background-radius: 4;");
        exportPdf.setOnAction(e -> {
            exportPdf.setDisable(true);
            exportPdf.setText(QTraceI18n.t("report.pdf.generating"));
            if (log != null) log.accept(QTraceI18n.t("report.pdf.generating"));
            ep.exportReportPdf(markdown).thenAccept(pdfBytes -> Platform.runLater(() -> {
                exportPdf.setDisable(false);
                exportPdf.setText(QTraceI18n.t("report.export.pdf"));
                if (pdfBytes == null || pdfBytes.length == 0) {
                    if (log != null) log.accept(QTraceI18n.t("report.pdf.failed"));
                    return;
                }
                savePdf(stage, qtrace, pdfBytes, log);
            })).exceptionally(t -> {
                Platform.runLater(() -> {
                    exportPdf.setDisable(false);
                    exportPdf.setText(QTraceI18n.t("report.export.pdf"));
                    if (log != null) log.accept(QTraceI18n.t("report.pdf.failed"));
                });
                return null;
            });
        });

        HBox buttons = new HBox(8, save, exportPdf);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, area, buttons);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: " + BG_BASE + ";");

        stage.setScene(new Scene(root, 720, 560));
        stage.show();
    }

    private static void savePdf(Stage stage, Path qtrace, byte[] pdfBytes, Consumer<String> log) {
        String base = qtrace.getFileName().toString();
        if (base.endsWith(".qtrace")) base = base.substring(0, base.length() - ".qtrace".length());

        FileChooser chooser = new FileChooser();
        chooser.setTitle(QTraceI18n.t("report.export.pdf"));
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        Path parent = qtrace.getParent();
        if (parent != null) chooser.setInitialDirectory(parent.toFile());
        chooser.setInitialFileName(base + ".report.pdf");

        java.io.File target = chooser.showSaveDialog(stage);
        if (target == null) return;
        try {
            Files.write(target.toPath(), pdfBytes);
            if (log != null) log.accept(QTraceI18n.t("report.pdf.saved") + " " + target.getName());
        } catch (Exception ex) {
            if (log != null) log.accept(QTraceI18n.t("report.error") + ": " + ex.getMessage());
        }
    }

    private static void saveMarkdown(Stage stage, Path qtrace, String markdown, Consumer<String> log) {
        String base = qtrace.getFileName().toString();
        if (base.endsWith(".qtrace")) base = base.substring(0, base.length() - ".qtrace".length());

        FileChooser chooser = new FileChooser();
        chooser.setTitle(QTraceI18n.t("report.save"));
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Markdown", "*.md"));
        Path parent = qtrace.getParent();
        if (parent != null) chooser.setInitialDirectory(parent.toFile());
        chooser.setInitialFileName(base + ".report.md");

        java.io.File target = chooser.showSaveDialog(stage);
        if (target == null) return;
        try {
            Files.write(target.toPath(), markdown.getBytes(StandardCharsets.UTF_8));
            if (log != null) log.accept(QTraceI18n.t("report.saved") + " " + target.getName());
        } catch (Exception ex) {
            if (log != null) log.accept(QTraceI18n.t("report.error") + ": " + ex.getMessage());
        }
    }
}
