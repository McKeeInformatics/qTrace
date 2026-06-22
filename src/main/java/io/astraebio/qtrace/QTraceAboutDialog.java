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

    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BG_CARD    = "#24273a";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";   // Core
    private static final String YELLOW     = "#f9e2af";   // Enterprise
    private static final String GREEN      = "#a6e3a1";   // Certified
    private static final String TEAL       = "#94e2d5";
    private static final String ORANGE     = "#fab387";
    private static final String RED        = "#f38ba8";   // invalid/tampered license

    private static final String WEBSITE    = "https://astraebio.com";
    private static final String PORTAL_URL = "https://qtrace.ca/portal";

    enum Mode { CORE, ENTERPRISE, CERTIFIED }

    public static void show(QuPathGUI qupath) {
        boolean hasEnterprise = QTracePluginManager.hasEnterprise();
        LicenseInfo activeLicense = null;
        if (hasEnterprise) {
            QTracePlugin ep = QTracePluginManager.get();
            if (ep != null) activeLicense = ep.getActiveLicenseInfo();
        }
        Mode mode = !hasEnterprise ? Mode.CORE
                  : (activeLicense != null ? Mode.CERTIFIED : Mode.ENTERPRISE);

        // Enterprise JAR present but license inactive/expired (a license is configured
        // yet not entitled) — signal the degraded state rather than a fresh Enterprise.
        boolean inactive = hasEnterprise
            && !QTracePluginManager.isEntitled()
            && !QTraceConfig.get().getLicensePath().isBlank();

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

        double[] drag = {0, 0};
        root.setOnMousePressed(e -> { drag[0] = e.getSceneX(); drag[1] = e.getSceneY(); });
        root.setOnMouseDragged(e -> {
            dialog.setX(e.getScreenX() - drag[0]);
            dialog.setY(e.getScreenY() - drag[1]);
        });

        root.getChildren().add(buildTitleBar(dialog));
        root.getChildren().add(buildHero(logo, mode, inactive));
        if (mode == Mode.CERTIFIED) root.getChildren().add(buildCertCard(activeLicense));
        root.getChildren().add(hRule());
        root.getChildren().add(buildFeatureGrid(mode));
        root.getChildren().add(hRule());
        root.getChildren().add(buildFooter(mode, inactive));

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
        Button x = ghostButton("✕", TEXT_MUTED, "#f38ba8", 13);
        x.setOnAction(e -> dialog.close());
        bar.getChildren().add(x);
        return bar;
    }

    // ── Hero ──────────────────────────────────────────────────────────────────

    private static VBox buildHero(Image logo, Mode mode, boolean inactive) {
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

        String badgeColor  = inactive ? inactiveColor() : switch (mode) {
            case CORE       -> BLUE;
            case ENTERPRISE -> YELLOW;
            case CERTIFIED  -> GREEN;
        };
        String badgeText   = inactive ? "Enterprise — " + inactiveWord() : switch (mode) {
            case CORE       -> "Core";
            case ENTERPRISE -> "Enterprise";
            case CERTIFIED  -> "✓ Certified";
        };

        Label badge = new Label(badgeText);
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

        Label version = new Label("v" + QTraceController.getDisplayVersion());
        version.setTextFill(Color.web(TEXT_MUTED));
        version.setFont(Font.font("System", 11));

        Label tagline = new Label("Workflow Provenance & Certification for QuPath");
        tagline.setTextFill(Color.web(TEXT_SUB));
        tagline.setFont(Font.font("System", FontPosture.ITALIC, 12));

        box.getChildren().addAll(nameRow, version, tagline);

        if (inactive) {
            Label warn = new Label("⚠ Your qTrace Enterprise license is no longer active — running in Core mode.");
            warn.setTextFill(Color.web(inactiveColor()));
            warn.setFont(Font.font("System", FontWeight.BOLD, 11));
            warn.setWrapText(true);
            warn.setMaxWidth(360);
            warn.setAlignment(Pos.CENTER);
            warn.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            box.getChildren().add(warn);
        }
        return box;
    }

    // ── Certified validator card ───────────────────────────────────────────────

    private static VBox buildCertCard(LicenseInfo li) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(0, 24, 14, 24));

        VBox inner = new VBox(5);
        inner.setPadding(new Insets(12, 16, 12, 16));
        inner.setStyle(
            "-fx-background-color: " + BG_CARD + ";"
          + "-fx-border-color: " + GREEN + "44;"
          + "-fx-border-radius: 8; -fx-background-radius: 8;"
        );

        Label titleLbl = new Label("✓  Licensed validator");
        titleLbl.setTextFill(Color.web(GREEN));
        titleLbl.setFont(Font.font("System", FontWeight.BOLD, 11));

        Label nameLbl = new Label(li.name());
        nameLbl.setTextFill(Color.web(TEXT_MAIN));
        nameLbl.setFont(Font.font("System", FontWeight.BOLD, 15));

        Label instLbl = new Label(li.institution().isBlank() ? "" : li.institution());
        instLbl.setTextFill(Color.web(TEXT_SUB));
        instLbl.setFont(Font.font("System", 12));

        HBox metaRow = new HBox(16);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        Label expLbl = new Label("Valid until  " + li.expiresAtFormatted());
        expLbl.setTextFill(Color.web(TEXT_MUTED));
        expLbl.setFont(Font.font("System", 11));

        metaRow.getChildren().add(expLbl);

        inner.getChildren().add(titleLbl);
        inner.getChildren().add(nameLbl);
        if (!li.institution().isBlank()) inner.getChildren().add(instLbl);
        inner.getChildren().add(metaRow);

        card.getChildren().add(inner);
        return card;
    }

    // ── Feature comparison grid ───────────────────────────────────────────────

    private static GridPane buildFeatureGrid(Mode mode) {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(6);
        g.setPadding(new Insets(16, 24, 16, 24));

        ColumnConstraints featureCol = new ColumnConstraints();
        featureCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints coreCol = new ColumnConstraints(56);
        coreCol.setHalignment(HPos.CENTER);
        ColumnConstraints entCol = new ColumnConstraints(76);
        entCol.setHalignment(HPos.CENTER);
        ColumnConstraints certCol = new ColumnConstraints(72);
        certCol.setHalignment(HPos.CENTER);
        g.getColumnConstraints().addAll(featureCol, coreCol, entCol, certCol);

        int row = 0;

        // Column headers — active column highlighted
        Label entHeader  = colHeader("Enterprise", mode == Mode.ENTERPRISE ? YELLOW : TEXT_MUTED);
        Label certHeader = colHeader("Certified",  mode == Mode.CERTIFIED  ? GREEN  : TEXT_MUTED);
        g.add(colHeader("", TEXT_MUTED), 0, row);
        g.add(colHeader("Core",  mode == Mode.CORE ? BLUE : TEXT_MUTED), 1, row);
        g.add(entHeader,  2, row);
        g.add(certHeader, 3, row);
        row++;

        Region r0 = gridRule(); g.add(r0, 0, row); GridPane.setColumnSpan(r0, 4); row++;

        // Shared by all editions
        String[] shared = {
            "Workflow step capture (real-time)",
            "Reproducible Meta-Script (Groovy)",
            "Git versioning via JGit",
            ".qtrace JSON audit trail export",
            "English / French UI",
        };
        for (String feat : shared) {
            g.add(featureLabel(feat, false), 0, row);
            g.add(check(true,  BLUE),   1, row);
            g.add(check(true,  YELLOW), 2, row);
            g.add(check(true,  GREEN),  3, row);
            row++;
        }

        Region r1 = gridRule(); g.add(r1, 0, row); GridPane.setColumnSpan(r1, 4); row++;

        // Enterprise + Certified
        String[] enterprise = {
            "Dashboard — multi-image viewer",
            "Batch export for full cohorts",
            "Validation Stamp (expert sign-off)",
            "Priority support by AstraeBio",
        };
        for (String feat : enterprise) {
            g.add(featureLabel(feat, mode == Mode.CORE), 0, row);
            g.add(check(false, BLUE),   1, row);
            g.add(check(true,  YELLOW), 2, row);
            g.add(check(true,  GREEN),  3, row);
            row++;
        }

        Region r2 = gridRule(); g.add(r2, 0, row); GridPane.setColumnSpan(r2, 4); row++;

        // Certified only
        String[] certified = {
            "Identity-certified stamps",
            "Validator name locked in stamp",
        };
        for (String feat : certified) {
            boolean dimmed = mode != Mode.CERTIFIED;
            g.add(featureLabel(feat, dimmed), 0, row);
            g.add(check(false, BLUE),   1, row);
            g.add(check(false, YELLOW), 2, row);
            g.add(check(true,  GREEN),  3, row);
            row++;
        }

        return g;
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private static HBox buildFooter(Mode mode, boolean inactive) {
        HBox footer = new HBox(8);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 24, 16, 24));
        footer.setStyle(
            "-fx-background-color: " + BG_SURFACE + ";"
          + "-fx-background-radius: 0 0 10 10;"
        );

        String licenseText = switch (mode) {
            case CORE       -> "·  Apache 2.0 License";
            case ENTERPRISE -> "·  Commercial License";
            case CERTIFIED  -> "·  Commercial License";
        };
        Label copy    = muted("© 2025 AstraeBio");
        Label license = muted(licenseText);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        footer.getChildren().addAll(copy, license, spacer);

        if (inactive) {
            boolean error = QTraceController.entitlementIsError();
            String c = inactiveColor();
            Button renew = new Button(error ? "Regenerate key →" : "Renew license →");
            renew.setStyle(
                "-fx-background-color: " + c + "22;"
              + "-fx-border-color: " + c + "66;"
              + "-fx-border-radius: 5; -fx-background-radius: 5;"
              + "-fx-text-fill: " + c + ";"
              + "-fx-font-size: 11; -fx-font-weight: bold;"
              + "-fx-cursor: hand; -fx-padding: 4 10 4 10;"
            );
            renew.setOnAction(e -> openUrl(PORTAL_URL));
            footer.getChildren().add(renew);
        } else if (mode == Mode.CORE) {
            Button upgrade = new Button("Get Enterprise ↑");
            upgrade.setStyle(
                "-fx-background-color: " + YELLOW + "22;"
              + "-fx-border-color: " + YELLOW + "66;"
              + "-fx-border-radius: 5; -fx-background-radius: 5;"
              + "-fx-text-fill: " + YELLOW + ";"
              + "-fx-font-size: 11; -fx-font-weight: bold;"
              + "-fx-cursor: hand; -fx-padding: 4 10 4 10;"
            );
            upgrade.setOnAction(e -> openUrl(PORTAL_URL));
            footer.getChildren().add(upgrade);
        } else if (mode == Mode.ENTERPRISE) {
            Button cert = new Button("Load license →");
            cert.setStyle(
                "-fx-background-color: " + GREEN + "22;"
              + "-fx-border-color: " + GREEN + "66;"
              + "-fx-border-radius: 5; -fx-background-radius: 5;"
              + "-fx-text-fill: " + GREEN + ";"
              + "-fx-font-size: 11; -fx-font-weight: bold;"
              + "-fx-cursor: hand; -fx-padding: 4 10 4 10;"
            );
            cert.setOnAction(e -> openUrl(PORTAL_URL));
            footer.getChildren().add(cert);
        } else {
            Button website = ghostButton("qtrace.ca ↗", TEAL, TEAL, 11);
            website.setOnAction(e -> openUrl(PORTAL_URL));
            footer.getChildren().add(website);
        }

        return footer;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Word shown in the badge for an inactive license: corrupted / expired / inactive. */
    private static String inactiveWord() {
        String r = QTracePluginManager.inactiveReason();
        if ("corrupted".equals(r)) return "corrupted";
        if ("expired".equals(r))   return "expired";
        return "inactive";
    }

    /** Red for a security failure (invalid signature), amber otherwise. */
    private static String inactiveColor() {
        return QTraceController.entitlementIsError() ? RED : ORANGE;
    }

    private static Label colHeader(String text, String color) {
        Label l = new Label(text);
        l.setTextFill(Color.web(color));
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

    private static Label check(boolean present, String activeColor) {
        Label l = new Label(present ? "✓" : "—");
        l.setTextFill(Color.web(present ? activeColor : TEXT_MUTED));
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
