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

import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * "Bug or Feature Request" dialog — same intake as the portal BackOffice, sent through
 * {@link IssueReportClient}. Screenshots can be pasted (Ctrl+V) or added from disk.
 *
 * Requires an active license: the reporter identity is the certified licensee, resolved
 * server-side from the .qtlicense JWT, so a Core-only install cannot submit.
 */
public final class IssueReportDialog {

    // Catppuccin Mocha — matches QTraceSettingsDialog / QTracePanel
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";
    private static final String RED        = "#f38ba8";
    private static final String FIELD =
        "-fx-background-color: " + BG_SURFACE + "; -fx-control-inner-background: " + BG_SURFACE + ";"
      + "-fx-text-fill: " + TEXT_MAIN + "; -fx-prompt-text-fill: " + TEXT_MUTED + ";"
      + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12;";

    private static final int BUG_INDEX = 1; // position of "Bug" in the type list

    private IssueReportDialog() {}

    public static void show(QuPathGUI qupath) {
        if (!QTracePluginManager.isEntitled() || QTraceUpdater.licenseJwt() == null) {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle(QTraceI18n.t("report.title"));
            a.setHeaderText(null);
            a.setContentText(QTraceI18n.t("report.needs.license"));
            if (qupath != null && qupath.getStage() != null) a.initOwner(qupath.getStage());
            a.show();
            return;
        }

        Stage dlg = new Stage();
        if (qupath != null) dlg.initOwner(qupath.getStage());
        dlg.initModality(Modality.WINDOW_MODAL);
        dlg.setTitle(QTraceI18n.t("report.title"));

        TextField tfTitle = new TextField();
        tfTitle.setPromptText(QTraceI18n.t("report.field.title"));
        tfTitle.setPrefHeight(30);
        tfTitle.setStyle(FIELD);
        HBox.setHgrow(tfTitle, Priority.ALWAYS);

        ComboBox<String> cbType = new ComboBox<>();
        // Feature request first, and the default; Bug second (BUG_INDEX).
        cbType.getItems().addAll(QTraceI18n.t("report.type.feature"), QTraceI18n.t("report.type.bug"));
        cbType.getSelectionModel().selectFirst();
        cbType.setPrefHeight(30);
        cbType.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-border-color: " + BORDER + ";"
            + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-font-size: 12;");

        String licensee = licenseeName();
        Label lblBy = new Label(QTraceI18n.t("report.by") + " " + licensee);
        lblBy.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 11;");

        HBox top = new HBox(8, tfTitle, cbType, lblBy);
        top.setAlignment(Pos.CENTER_LEFT);

        TextArea taDesc = new TextArea();
        taDesc.setPromptText(QTraceI18n.t("report.field.description"));
        taDesc.setWrapText(true);
        taDesc.setPrefRowCount(8);
        taDesc.setStyle(FIELD);
        VBox.setVgrow(taDesc, Priority.ALWAYS);

        // ── Screenshots ────────────────────────────────────────────────────────
        List<byte[]> images = new ArrayList<>();
        FlowPane thumbs = new FlowPane(8, 8);
        Label lblHint = new Label(QTraceI18n.t("report.images.hint"));
        lblHint.setStyle("-fx-text-fill: " + TEXT_MUTED + "; -fx-font-size: 11;");
        Label lblStatus = new Label();
        lblStatus.setWrapText(true);
        lblStatus.setStyle("-fx-text-fill: " + RED + "; -fx-font-size: 11;");

        Runnable[] refresh = new Runnable[1];
        Runnable addFailed = () -> lblStatus.setText(QTraceI18n.t("report.err.image"));
        refresh[0] = () -> {
            thumbs.getChildren().clear();
            for (int i = 0; i < images.size(); i++) {
                final int idx = i;
                ImageView iv = new ImageView(new Image(new ByteArrayInputStream(images.get(i))));
                iv.setFitHeight(64);
                iv.setPreserveRatio(true);
                Button rm = new Button("×");
                rm.setStyle("-fx-background-color: " + BG_BASE + "; -fx-text-fill: " + TEXT_MAIN + ";"
                    + "-fx-background-radius: 10; -fx-padding: 0 5 1 5; -fx-cursor: hand;");
                rm.setOnAction(e -> { images.remove(idx); refresh[0].run(); });
                StackPane cell = new StackPane(iv, rm);
                StackPane.setAlignment(rm, Pos.TOP_RIGHT);
                thumbs.getChildren().add(cell);
            }
            lblHint.setText(images.isEmpty()
                ? QTraceI18n.t("report.images.hint")
                : images.size() + " / " + IssueReportClient.MAX_IMAGES);
        };

        java.util.function.Consumer<byte[]> addImage = png -> {
            if (png == null) { addFailed.run(); return; }
            if (images.size() >= IssueReportClient.MAX_IMAGES) {
                lblStatus.setText(QTraceI18n.t("report.err.toomany"));
                return;
            }
            lblStatus.setText("");
            images.add(png);
            refresh[0].run();
        };

        // Ctrl+V on the whole dialog (title, description…): an image on the clipboard is
        // attached and the event consumed; plain text falls through to the focused field.
        dlg.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.V && (ev.isControlDown() || ev.isMetaDown())) {
                Clipboard cb = Clipboard.getSystemClipboard();
                if (cb.hasFiles()) {
                    boolean any = false;
                    for (File f : cb.getFiles()) {
                        byte[] png = readImageFile(f);
                        if (png != null) { addImage.accept(png); any = true; }
                    }
                    if (any) ev.consume();
                } else if (cb.hasImage() && !cb.hasString()) {
                    addImage.accept(toPng(cb.getImage()));
                    ev.consume();
                }
            }
        });

        Button btnAdd = new Button(QTraceI18n.t("report.images.add"));
        btnAdd.setStyle("-fx-background-color: transparent; -fx-text-fill: " + TEXT_SUB + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 6; -fx-background-radius: 6;"
            + "-fx-cursor: hand; -fx-font-size: 12;");
        btnAdd.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
            List<File> files = fc.showOpenMultipleDialog(dlg);
            if (files != null) for (File f : files) addImage.accept(readImageFile(f));
        });
        HBox imgBar = new HBox(8, btnAdd, lblHint);
        imgBar.setAlignment(Pos.CENTER_LEFT);

        // ── Actions ────────────────────────────────────────────────────────────
        Button btnSend = new Button(QTraceI18n.t("report.send"));
        btnSend.setDefaultButton(false);
        btnSend.setPadding(new Insets(6, 18, 6, 18));
        btnSend.setStyle("-fx-background-color: " + BLUE + "; -fx-text-fill: " + BG_BASE + ";"
            + "-fx-background-radius: 6; -fx-cursor: hand; -fx-font-size: 12; -fx-font-weight: bold;");
        Button btnCancel = new Button(QTraceI18n.t("report.cancel"));
        btnCancel.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;"
            + "-fx-text-fill: " + TEXT_SUB + "; -fx-cursor: hand; -fx-font-size: 12;");
        btnCancel.setOnAction(e -> dlg.close());
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.setStyle("-fx-progress-color: " + BLUE + ";");
        spinner.setVisible(false);

        btnSend.setOnAction(e -> {
            String title = tfTitle.getText().trim();
            String desc = taDesc.getText().trim();
            if (title.isEmpty() || desc.isEmpty()) {
                lblStatus.setText(QTraceI18n.t("report.err.empty"));
                return;
            }
            String jwt = QTraceUpdater.licenseJwt();
            if (jwt == null) { lblStatus.setText(QTraceI18n.t("report.err.license")); return; }

            boolean bug = cbType.getSelectionModel().getSelectedIndex() == BUG_INDEX;
            List<byte[]> sent = new ArrayList<>(images);
            // A bug is read against the workstation it happened on. What needs the JavaFX
            // thread is read here; the rest (it may ask the OS) on the sending thread.
            String screens = bug ? SystemInfo.screens() : null;
            com.google.gson.JsonArray extensions = bug && qupath != null
                ? QTraceController.collectLoadedExtensions(qupath) : null;
            java.nio.file.Path projectDir = QTraceConfig.currentProjectDir();

            btnSend.setDisable(true);
            spinner.setVisible(true);
            lblStatus.setText("");
            Thread t = new Thread(() -> {
                JsonObject body = bug
                    ? IssueReportClient.buildBody(title, desc, true, sent,
                        SystemInfo.environment(qupathVersion(), screens, extensions, projectDir))
                    : IssueReportClient.buildBody(title, desc, false, sent,
                        QTraceController.VERSION, qupathVersion(), System.getProperty("os.name"));
                IssueReportClient.Result r = IssueReportClient.send(jwt, body);
                Platform.runLater(() -> {
                    spinner.setVisible(false);
                    if (r.ok()) {
                        Alert a = new Alert(Alert.AlertType.INFORMATION);
                        a.setTitle(QTraceI18n.t("report.title"));
                        a.setHeaderText(null);
                        a.setContentText(QTraceI18n.t("report.sent").replace("{0}", String.valueOf(r.issueNumber())));
                        a.initOwner(dlg);
                        dlg.close();
                        a.show();
                    } else {
                        btnSend.setDisable(false);
                        lblStatus.setText(QTraceI18n.t(IssueReportClient.messageKey(r.status())));
                    }
                });
            }, "qtrace-issue-report");
            t.setDaemon(true);
            t.start();
        });

        HBox actions = new HBox(8, spinner, btnCancel, btnSend);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(8, 20, 16, 20));

        Label lblEnv = new Label(QTraceI18n.t("report.env.notice"));
        lblEnv.setWrapText(true);
        lblEnv.setStyle("-fx-text-fill: " + TEXT_MUTED + "; -fx-font-size: 11;");
        lblEnv.visibleProperty().bind(cbType.getSelectionModel().selectedIndexProperty().isEqualTo(BUG_INDEX));

        // Same shell as Settings: title bar on the darker surface, content, buttons at the bottom right.
        Label header = new Label(QTraceI18n.t("report.title").replace("qTrace — ", ""));
        header.setMaxWidth(Double.MAX_VALUE);
        header.setPadding(new Insets(12, 20, 12, 20));
        header.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-text-fill: " + TEXT_MAIN + ";"
            + "-fx-font-size: 13; -fx-font-weight: bold;");

        VBox content = new VBox(10, top, taDesc, imgBar, thumbs, lblEnv, lblStatus);
        content.setPadding(new Insets(16, 20, 4, 20));
        VBox.setVgrow(content, Priority.ALWAYS);

        VBox root = new VBox(header, content, actions);
        // -fx-base makes every control (drop-down list, scroll bars) dark with light text.
        root.setStyle("-fx-background-color: " + BG_BASE + "; -fx-base: " + BG_BASE + ";"
            + "-fx-control-inner-background: " + BG_SURFACE + "; -fx-accent: " + BLUE + ";"
            + "-fx-focus-color: " + BLUE + "; -fx-faint-focus-color: transparent;");
        Scene scene = new Scene(root, 640, 520);
        // Esc = Cancel, as in Settings.
        scene.addEventHandler(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) { btnCancel.fire(); ev.consume(); }
        });
        dlg.setScene(scene);
        Image logo = QTracePanel.loadLogo();
        if (logo != null) dlg.getIcons().add(logo);
        dlg.show();
    }

    private static String licenseeName() {
        QTracePlugin p = QTracePluginManager.get();
        LicenseInfo li = p != null ? p.getActiveLicenseInfo() : null;
        return li != null && li.name() != null ? li.name() : "—";
    }

    static String qupathVersion() {
        try { return String.valueOf(QuPathGUI.getVersion()); } catch (Throwable t) { return null; }
    }

    private static byte[] readImageFile(File f) {
        try {
            String n = f.getName().toLowerCase();
            if (!(n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".bmp"))) return null;
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(f.toPath())));
            return bi == null ? null : encodeWithinLimit(bi);
        } catch (Exception e) {
            return null;
        }
    }

    /** JavaFX clipboard image → PNG bytes (pixel copy; avoids initialising AWT/Swing). */
    static byte[] toPng(Image img) {
        if (img == null) return null;
        PixelReader pr = img.getPixelReader();
        if (pr == null) return null;
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        if (w <= 0 || h <= 0) return null;
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) bi.setRGB(x, y, pr.getArgb(x, y));
        return encodeWithinLimit(bi);
    }

    /** PNG-encode, halving dimensions until under the server's per-image limit. */
    static byte[] encodeWithinLimit(BufferedImage bi) {
        try {
            BufferedImage cur = bi;
            for (int i = 0; i < 6; i++) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!ImageIO.write(cur, "png", out)) return null;
                if (out.size() <= IssueReportClient.MAX_IMAGE_BYTES) return out.toByteArray();
                int nw = Math.max(1, cur.getWidth() / 2), nh = Math.max(1, cur.getHeight() / 2);
                BufferedImage s = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
                var g = s.createGraphics();
                g.drawImage(cur.getScaledInstance(nw, nh, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
                g.dispose();
                cur = s;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
