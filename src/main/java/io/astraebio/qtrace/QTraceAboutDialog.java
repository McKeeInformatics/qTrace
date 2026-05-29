package io.astraebio.qtrace;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import qupath.lib.gui.QuPathGUI;

import java.awt.Desktop;
import java.net.URI;

public class QTraceAboutDialog {

    // Catppuccin Mocha — identical palette to QTracePanel
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";
    private static final String YELLOW     = "#f9e2af";  // Enterprise gold
    private static final String RED        = "#f38ba8";
    private static final String TEAL       = "#94e2d5";

    private static final String WEBSITE = "https://astraebio.com";

    public static void show(QuPathGUI qupath) {
        boolean enterprise = QTracePluginManager.hasEnterprise();

        Stage dialog = new Stage();
        dialog.initOwner(qupath.getStage());
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.setResizable(false);

        Image logo = QTracePanel.loadLogo();
        if (logo != null) dialog.getIcons().add(logo);

        VBox root = new VBox();
        root.setStyle(
            "-fx-background-color: " + BG_BASE + ";"
          + "-fx-border-color: " + BORDER + ";"
          + "-fx-border-width: 1;"
          + "-fx-border-radius: 10;"
          + "-fx-background-radius: 10;"
        );

        // drag-to-move
        double[] drag = {0, 0};
        root.setOnMousePressed(e -> { drag[0] = e.getSceneX(); drag[1] = e.getSceneY(); });
        root.setOnMouseDragged(e -> {
            dialog.setX(e.getScreenX() - drag[0]);
            dialog.setY(e.getScreenY() - drag[1]);
        });

        root.getChildren().addAll(
            buildTitleBar(dialog),
            buildHero(logo, enterprise),
            hRule(),
            buildFeatureGrid(enterprise),
            hRule(),
            buildFooter(enterprise)
        );

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) dialog.close(); });

        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.centerOnScreen();
        dialog.show();
    }

    // ── Title bar ─────────────────────────────────────────────────────────────

    private static HBox buildTitleBar(Stage dialog) {
        HBox bar = new HBox();
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8, 12, 0, 12));
        Button x = ghostButton("✕", TEXT_MUTED, RED, 13);
        x.setOnAction(e -> dialog.close());
        bar.getChildren().add(x);
        return bar;
    }

    // ── Hero ──────────────────────────────────────────────────────────────────

    private static VBox buildHero(Image logo, boolean enterprise) {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(4, 28, 20, 28));

        if (logo != null) {
            ImageView iv = new ImageView(logo);
            iv.setFitHeight(72);
            iv.setPreserveRatio(true);
            box.getChildren().add(iv);
        }

        Text name = new Text("qTrace");
        name.setFont(Font.font("System", FontWeight.BOLD, 30));
        name.setFill(Color.web(TEXT_MAIN));

        String badgeColor = enterprise ? YELLOW : BLUE;
        Label badge = new Label(enterprise ? "Enterprise" : "Core");
        badge.setStyle(
            "-fx-background-color: " + badgeColor + "22;"
          + "-fx-border-color: " + badgeColor + "66;"
          + "-fx-border-radius: 5; -fx-background-radius: 5;"
          + "-fx-text-fill: " + badgeColor + ";"
          + "-fx-font-size: 12; -fx-font-weight: bold;"
          + "-fx-padding: 2 10 2 10;"
        );

        HBox nameRow = new HBox(10);
        nameRow.setAlignment(Pos.CENTER);
        nameRow.getChildren().addAll(name, badge);

        Label version = new Label("v" + QTraceController.VERSION);
        version.setTextFill(Color.web(TEXT_MUTED));
        version.setFont(Font.font("System", 11));

        Label tagline = new Label("Workflow Provenance & Certification for QuPath");
        tagline.setTextFill(Color.web(TEXT_SUB));
        tagline.setFont(Font.font("System", FontPosture.ITALIC, 12));

        box.getChildren().addAll(nameRow, version, tagline);
        return box;
    }

    // ── Feature comparison grid ───────────────────────────────────────────────

    private static GridPane buildFeatureGrid(boolean enterprise) {
        GridPane g = new GridPane();
        g.setHgap(16);
        g.setVgap(6);
        g.setPadding(new Insets(16, 28, 16, 28));

        ColumnConstraints featureCol = new ColumnConstraints();
        featureCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints coreCol = new ColumnConstraints(64);
        coreCol.setHalignment(HPos.CENTER);
        ColumnConstraints entCol = new ColumnConstraints(84);
        entCol.setHalignment(HPos.CENTER);
        g.getColumnConstraints().addAll(featureCol, coreCol, entCol);

        int row = 0;

        // Column headers
        Label entHeader = colHeader("Enterprise");
        if (enterprise) entHeader.setTextFill(Color.web(YELLOW));
        g.add(colHeader(""), 0, row);
        g.add(colHeader("Core"), 1, row);
        g.add(entHeader, 2, row);
        row++;

        Region r0 = gridRule();
        g.add(r0, 0, row);
        GridPane.setColumnSpan(r0, 3);
        row++;

        // Features included in both editions
        String[] shared = {
            "Workflow step capture (real-time)",
            "Reproducible Meta-Script (Groovy)",
            "Git versioning via JGit",
            ".qtrace JSON audit trail export",
            "English / French UI",
        };
        for (String feat : shared) {
            g.add(featureLabel(feat, false), 0, row);
            g.add(check(true, false), 1, row);
            g.add(check(true, true),  2, row);
            row++;
        }

        Region r1 = gridRule();
        g.add(r1, 0, row);
        GridPane.setColumnSpan(r1, 3);
        row++;

        // Enterprise-only features — dimmed when running Core
        String[] premium = {
            "Dashboard — multi-image viewer",
            "Batch export for full cohorts",
            "Validation Stamp (expert sign-off)",
            "Priority support by AstraeBio",
        };
        for (String feat : premium) {
            g.add(featureLabel(feat, !enterprise), 0, row);
            g.add(check(false, false), 1, row);
            g.add(check(true, true),   2, row);
            row++;
        }

        return g;
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private static HBox buildFooter(boolean enterprise) {
        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 28, 16, 28));
        footer.setStyle(
            "-fx-background-color: " + BG_SURFACE + ";"
          + "-fx-background-radius: 0 0 10 10;"
        );

        Label copy    = muted("© 2025 AstraeBio");
        Label license = muted("·  " + (enterprise ? "Commercial License" : "Apache 2.0 License"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button website = ghostButton("astraebio.com ↗", TEAL, TEAL, 11);
        website.setOnAction(e -> openUrl(WEBSITE));

        footer.getChildren().addAll(copy, license, spacer, website);

        if (!enterprise) {
            Button upgrade = new Button("Upgrade ↑");
            upgrade.setStyle(
                "-fx-background-color: " + YELLOW + "22;"
              + "-fx-border-color: " + YELLOW + "66;"
              + "-fx-border-radius: 5; -fx-background-radius: 5;"
              + "-fx-text-fill: " + YELLOW + ";"
              + "-fx-font-size: 11; -fx-font-weight: bold;"
              + "-fx-cursor: hand;"
              + "-fx-padding: 4 10 4 10;"
            );
            upgrade.setOnAction(e -> openUrl(WEBSITE + "/enterprise"));
            footer.getChildren().add(upgrade);
        }

        return footer;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Label colHeader(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web(TEXT_MUTED));
        l.setFont(Font.font("System", FontWeight.BOLD, 10));
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        return l;
    }

    private static Label featureLabel(String text, boolean dimmed) {
        Label l = new Label(text);
        l.setTextFill(Color.web(dimmed ? TEXT_MUTED : TEXT_SUB));
        l.setFont(Font.font("System", 12));
        return l;
    }

    private static Label check(boolean present, boolean isEnterpriseColumn) {
        Label l = new Label(present ? "✓" : "—");
        String color = present ? (isEnterpriseColumn ? YELLOW : GREEN) : TEXT_MUTED;
        l.setTextFill(Color.web(color));
        l.setFont(Font.font("System", FontWeight.BOLD, 13));
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        return l;
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.setTextFill(Color.web(TEXT_MUTED));
        l.setFont(Font.font("System", 11));
        return l;
    }

    private static Region hRule() {
        Region r = new Region();
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color: " + BORDER + ";");
        return r;
    }

    private static Region gridRule() {
        Region r = new Region();
        r.setPrefHeight(1);
        r.setStyle("-fx-background-color: " + BORDER + ";");
        GridPane.setMargin(r, new Insets(4, 0, 4, 0));
        return r;
    }

    private static Button ghostButton(String text, String fg, String hover, int size) {
        Button b = new Button(text);
        String base = "-fx-background-color:transparent;-fx-text-fill:" + fg
                    + ";-fx-cursor:hand;-fx-font-size:" + size + ";-fx-padding:2 4 2 4;";
        String over = "-fx-background-color:transparent;-fx-text-fill:" + hover
                    + ";-fx-cursor:hand;-fx-font-size:" + size + ";-fx-padding:2 4 2 4;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(over));
        b.setOnMouseExited(e  -> b.setStyle(base));
        return b;
    }

    private static void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported())
                Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ignored) {}
    }
}
