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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.collections.FXCollections;
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
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Batch export — generates MetaScript + ValidationStamp + .qtrace for every
 * image in the active QuPath project without user interaction per image.
 *
 * The user fills in validator / scope / confidence once in the pre-flight
 * dialog; the batch engine then opens each image, waits for qTrace to attach
 * and the SHA-256 hash to be ready, and calls
 * {@link QTraceController#programmaticExport} on the JavaFX thread.
 */
public class QTraceBatchExporter {

    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BG_CARD    = "#24273a";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String GREEN      = "#a6e3a1";
    private static final String PEACH      = "#fab387";
    private static final String BLUE       = "#89b4fa";

    private QTraceBatchExporter() {}

    // ── Entry point ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static void start(QuPathGUI qupath, QTraceController controller) {
        var project = qupath.getProject();
        if (project == null) {
            alert(qupath.getStage(), Alert.AlertType.WARNING,
                "Batch Export", "No project loaded", "Open a QuPath project first.");
            return;
        }

        List<ProjectImageEntry<BufferedImage>> entries;
        try {
            entries = (List<ProjectImageEntry<BufferedImage>>) project.getImageList();
        } catch (Exception e) {
            alert(qupath.getStage(), Alert.AlertType.ERROR,
                "Batch Export", "Cannot read image list", e.getMessage());
            return;
        }

        if (entries.isEmpty()) {
            alert(qupath.getStage(), Alert.AlertType.INFORMATION,
                "Batch Export", "No images in project", null);
            return;
        }

        Optional<BatchConfig> cfg = showPreflight(qupath.getStage(), entries.size());
        if (cfg.isEmpty()) return;

        Stage progressStage = buildProgressStage(qupath.getStage(), entries.size());
        AtomicBoolean cancelled = new AtomicBoolean(false);

        ProgressBar bar    = (ProgressBar) progressStage.getScene().getRoot().lookup("#bar");
        Label       status = (Label)       progressStage.getScene().getRoot().lookup("#status");
        TextArea    log    = (TextArea)     progressStage.getScene().getRoot().lookup("#log");
        Button      cancel = (Button)       progressStage.getScene().getRoot().lookup("#cancel");
        cancel.setOnAction(e -> cancelled.set(true));

        progressStage.show();

        Thread t = new Thread(() ->
            runBatch(controller, entries, cfg.get(), cancelled,
                     progressStage, bar, status, log),
            "qtrace-batch");
        t.setDaemon(true);
        t.start();
    }

    // ── Pre-flight dialog ──────────────────────────────────────────────────────

    private record BatchConfig(String validator, String scope, String confidence,
                               String notes, boolean skipExisting) {}

    private static Optional<BatchConfig> showPreflight(Stage owner, int count) {
        Dialog<BatchConfig> dlg = new Dialog<>();
        dlg.initOwner(owner);
        dlg.setTitle("QTrace — Batch Export");
        dlg.setHeaderText("Batch export — " + count + " image(s)\nValidation stamp applied to all images.");

        TextField validatorFld = new TextField(QTraceConfig.get().getValidatorName());
        validatorFld.setPromptText("Dr. Lastname / Analyst ID");
        validatorFld.setPrefWidth(280);

        ComboBox<String> scopeBox = new ComboBox<>(FXCollections.observableArrayList(
            "Full Workflow", "Image QC", "Segmentation",
            "Feature Extraction", "Phenotyping", "Spatial Analysis"));
        scopeBox.setValue("Full Workflow");

        ComboBox<String> confidenceBox = new ComboBox<>(FXCollections.observableArrayList(
            "High", "Medium", "Low"));
        confidenceBox.setValue("High");

        TextArea notesFld = new TextArea();
        notesFld.setPromptText("Optional — applies to all images in this batch");
        notesFld.setPrefRowCount(2);
        notesFld.setWrapText(true);

        CheckBox skipCb = new CheckBox("Skip images that already have a .qtrace file");
        skipCb.setSelected(true);

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));
        int r = 0;
        grid.add(new Label("Validator *"), 0, r); grid.add(validatorFld,   1, r++);
        grid.add(new Label("Scope"),       0, r); grid.add(scopeBox,       1, r++);
        grid.add(new Label("Confidence"),  0, r); grid.add(confidenceBox,  1, r++);
        grid.add(new Label("Notes"),       0, r); grid.add(notesFld,       1, r++);
        grid.add(skipCb, 1, r);

        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okBtn = (Button) dlg.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setText("Start Batch Export");
        okBtn.setDisable(validatorFld.getText().trim().isEmpty());
        validatorFld.textProperty().addListener((obs, o, n) ->
            okBtn.setDisable(n == null || n.trim().isEmpty()));

        dlg.setResultConverter(btn -> {
            if (btn != ButtonType.OK) return null;
            return new BatchConfig(
                validatorFld.getText().trim(),
                scopeBox.getValue(),
                confidenceBox.getValue(),
                notesFld.getText().trim(),
                skipCb.isSelected()
            );
        });
        return dlg.showAndWait();
    }

    // ── Progress stage ─────────────────────────────────────────────────────────

    private static Stage buildProgressStage(Stage owner, int total) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("QTrace — Batch Export");
        stage.setWidth(540); stage.setHeight(420);
        stage.setResizable(true);

        Label title = lbl("Batch Export — " + total + " image(s)", TEXT_MAIN, 13, true);

        ProgressBar bar = new ProgressBar(0);
        bar.setId("bar"); bar.setMaxWidth(Double.MAX_VALUE);
        bar.setStyle("-fx-accent:" + BLUE + ";");

        Label status = lbl("Starting…", TEXT_SUB, 11, false);
        status.setId("status");

        TextArea log = new TextArea();
        log.setId("log"); log.setEditable(false);
        log.setStyle(
            "-fx-control-inner-background:" + BG_SURFACE + ";"
            + "-fx-background-color:" + BG_SURFACE + ";"
            + "-fx-text-fill:" + TEXT_SUB + ";"
            + "-fx-font-family:monospace;-fx-font-size:10;");
        VBox.setVgrow(log, Priority.ALWAYS);

        Button cancel = new Button("Cancel");
        cancel.setId("cancel");
        cancel.setStyle(
            "-fx-background-color:" + BG_CARD + ";-fx-text-fill:" + PEACH + ";"
            + "-fx-border-color:" + BORDER + ";-fx-border-radius:4;-fx-background-radius:4;"
            + "-fx-cursor:hand;-fx-padding:5 14;");

        HBox bottom = new HBox(cancel);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        VBox root = new VBox(10, title, bar, status, log, bottom);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color:" + BG_BASE + ";");

        stage.setScene(new Scene(root));
        return stage;
    }

    // ── Batch engine ───────────────────────────────────────────────────────────

    private static void runBatch(
            QTraceController controller,
            List<ProjectImageEntry<BufferedImage>> entries,
            BatchConfig cfg,
            AtomicBoolean cancelled,
            Stage progressStage,
            ProgressBar bar,
            Label status,
            TextArea log) {

        // Pre-scan existing .qtrace files to build a skip list
        Set<String> alreadyExported = new HashSet<>();
        if (cfg.skipExisting()) {
            try {
                Path exportDir = QTraceConfig.get().getExportDir();
                if (Files.exists(exportDir)) {
                    Files.list(exportDir)
                        .filter(p -> p.toString().endsWith(".qtrace"))
                        .forEach(p -> {
                            try {
                                JsonObject root = JsonParser.parseString(
                                    Files.readString(p)).getAsJsonObject();
                                if (root.has("image") && !root.get("image").isJsonNull()) {
                                    JsonObject img = root.getAsJsonObject("image");
                                    if (img.has("name"))
                                        alreadyExported.add(img.get("name").getAsString());
                                }
                            } catch (Exception ignored) {}
                        });
                }
            } catch (Exception ignored) {}
        }

        int total = entries.size(), done = 0, skipped = 0, errors = 0;

        for (int i = 0; i < entries.size(); i++) {
            if (cancelled.get()) { appendLog(log, "⛔  Cancelled."); break; }

            ProjectImageEntry<BufferedImage> entry = entries.get(i);
            String name = entry.getImageName();
            final int idx = i + 1;

            fx(() -> {
                status.setText("Processing " + idx + "/" + total + " — " + shorten(name));
                bar.setProgress((double) idx / (total + 1));
            });

            // Skip if already exported
            if (cfg.skipExisting()) {
                boolean found = alreadyExported.stream()
                    .anyMatch(n -> namesMatch(n, name));
                if (found) {
                    appendLog(log, "⏩  [" + idx + "/" + total + "] " + shorten(name)
                        + "  (skipped — .qtrace exists)");
                    skipped++;
                    continue;
                }
            }

            appendLog(log, "⏳  [" + idx + "/" + total + "] " + shorten(name) + "…");

            // Open image on FX thread
            CompletableFuture<Void> opened = new CompletableFuture<>();
            Platform.runLater(() -> {
                controller.openEntry(entry);
                opened.complete(null);
            });
            try { opened.get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}

            // Poll until qTrace attaches to the right image and SHA-256 is ready.
            // Timeout is generous (5 min) to accommodate large WSI files.
            ActionLogger lg = controller.getLogger();
            boolean loaded = false;
            long deadline = System.currentTimeMillis() + 300_000;
            long lastDot  = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
                if (cancelled.get()) break;
                if (lg == null) { lg = controller.getLogger(); continue; }
                var cur = lg.getCurrentImageData();
                if (cur != null && namesMatch(cur.getServer().getMetadata().getName(), name)
                        && lg.getImageHash() != null) {
                    loaded = true;
                    break;
                }
                // Progress dot every 5 s so the user sees loading is still active
                if (System.currentTimeMillis() - lastDot > 5_000) {
                    long elapsed = (System.currentTimeMillis() - (deadline - 300_000)) / 1000;
                    appendLog(log, "   ⌛  loading… (" + elapsed + "s)");
                    lastDot = System.currentTimeMillis();
                }
            }

            if (!loaded) {
                appendLog(log, "❌  [" + idx + "/" + total + "] " + shorten(name)
                    + "  (timeout — image did not load after 5 min)");
                errors++;
                continue;
            }

            // Skip if no workflow steps
            if (lg == null || !lg.hasSteps()) {
                appendLog(log, "⏩  [" + idx + "/" + total + "] " + shorten(name)
                    + "  (skipped — no steps captured)");
                skipped++;
                continue;
            }

            // Generate MetaScript + stamp + export on FX thread
            CompletableFuture<String> result = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    String filename = controller.programmaticExport(
                        cfg.validator(), cfg.scope(), cfg.confidence(), cfg.notes());
                    result.complete(filename);
                } catch (Exception e) {
                    result.completeExceptionally(e);
                }
            });

            try {
                String filename = result.get(30, TimeUnit.SECONDS);
                appendLog(log, "✅  [" + idx + "/" + total + "] " + shorten(name)
                    + "  →  " + filename);
                done++;
            } catch (Exception e) {
                String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                appendLog(log, "❌  [" + idx + "/" + total + "] " + shorten(name)
                    + "  (" + msg + ")");
                errors++;
            }

            // Breathing room between images
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }

        final int fDone = done, fSkipped = skipped, fErrors = errors;
        Platform.runLater(() -> {
            bar.setProgress(1.0);
            bar.setStyle("-fx-accent:" + (fErrors > 0 ? PEACH : GREEN) + ";");
            status.setText("Done — " + fDone + " exported  •  "
                + fSkipped + " skipped  •  " + fErrors + " error(s)");
            appendLog(log, "─────────────────────────────────────────────────────");
            appendLog(log, "Done  " + fDone + " exported   "
                + fSkipped + " skipped   " + fErrors + " error(s)");
            Button cancelBtn = (Button) progressStage.getScene().getRoot().lookup("#cancel");
            if (cancelBtn != null) {
                cancelBtn.setText("Close");
                cancelBtn.setOnAction(e -> progressStage.close());
            }
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static void fx(Runnable r)              { Platform.runLater(r); }

    private static void appendLog(TextArea area, String msg) {
        Platform.runLater(() -> {
            area.appendText(msg + "\n");
            area.setScrollTop(Double.MAX_VALUE);
        });
    }

    private static boolean namesMatch(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static String shorten(String s) {
        return s != null && s.length() > 50 ? s.substring(0, 47) + "…" : s != null ? s : "";
    }

    private static Label lbl(String text, String color, int size, boolean bold) {
        Label l = new Label(text);
        l.setTextFill(Color.web(color));
        l.setFont(Font.font("System", bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        return l;
    }

    private static void alert(Stage owner, Alert.AlertType type,
                              String title, String header, String content) {
        Alert a = new Alert(type);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(header);
        if (content != null) a.setContentText(content);
        a.showAndWait();
    }
}
