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

import com.google.gson.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Commit-graph window — visualizes a .qtrace as a git-like history (v1: linear "main" branch).
 *
 * Each session is one commit, drawn left→right and chained by {@code parent_session_id}.
 * A commit has exactly one author (the contributor who exported/stamped it): one badge per node.
 * Node title = stamp notes (→ scope → "Session #n"). The side panel answers "who did what":
 * the contributor and the categorized actions of that commit (from the {@code contributions}
 * block, which already excludes pre-tracking steps inherited from a prior contributor).
 *
 * Enterprise-only feature (opened from the panel), but lives in Core as it only reads JSON.
 */
public class QTraceCommitGraph {

    // ── Catppuccin Mocha (matches QTraceDashboard / QTracePanel) ───────────────
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BG_CARD    = "#24273a";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";
    private static final String PEACH      = "#fab387";
    private static final String RED        = "#f38ba8";

    // ── Layout constants ───────────────────────────────────────────────────────
    private static final double X_GAP   = 190;
    private static final double ORIGIN_X = 90;
    private static final double LANE_Y   = 130;
    private static final double NODE_R   = 20;

    // ── Node model ─────────────────────────────────────────────────────────────
    private static final class Node {
        String  id, parentId, branch;
        String  contributor, validator, confidence, fidelity, notes, scope, exportedAt, imageHashShort;
        boolean signed;
        int     stepsCaptured, preTracking;
        JsonObject contributions;   // { contributor, actions:{cat:count} }
        double  cx, cy;             // canvas centre (for hit-testing)
        int     index;
    }

    private final QuPathGUI qupath;
    private final Stage     stage;
    private final Canvas    canvas;
    private final VBox      detailBox;
    private final Label     headerLabel;
    private final List<Node> nodes = new ArrayList<>();

    public QTraceCommitGraph(QuPathGUI qupath) {
        this.qupath = qupath;
        this.stage  = new Stage();
        stage.setTitle(QTraceI18n.t("graph.window.title"));

        headerLabel = new Label("");
        headerLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
        headerLabel.setTextFill(Color.web(TEXT_MAIN));

        Button openBtn = new Button(QTraceI18n.t("graph.open"));
        styleButton(openBtn);
        openBtn.setOnAction(e -> chooseFile());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, headerLabel, spacer, openBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));
        header.setStyle("-fx-background-color: " + BG_CARD + ";");

        canvas = new Canvas(900, 320);
        Pane canvasPane = new Pane(canvas);
        canvasPane.setStyle("-fx-background-color: " + BG_BASE + ";");
        ScrollPane scroll = new ScrollPane(canvasPane);
        scroll.setStyle("-fx-background: " + BG_BASE + "; -fx-background-color: " + BG_BASE + ";");
        scroll.setFitToHeight(true);

        detailBox = new VBox(6);
        detailBox.setPadding(new Insets(12));
        detailBox.setPrefWidth(280);
        detailBox.setStyle("-fx-background-color: " + BG_SURFACE + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-width: 0 0 0 1;");
        showEmptyDetail();

        canvas.setOnMouseClicked(e -> {
            Node hit = nodeAt(e.getX(), e.getY());
            if (hit != null) { showDetail(hit); redraw(hit); }
        });

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(scroll);
        root.setRight(detailBox);
        root.setStyle("-fx-background-color: " + BG_BASE + ";");

        stage.setScene(new Scene(root, 1180, 460));
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Opens the graph for the given .qtrace, or prompts a chooser when {@code preselected} is missing. */
    public void show(File preselected) {
        if (preselected != null && preselected.isFile()) load(preselected);
        else chooseFile();
        stage.show();
        stage.toFront();
        stage.setIconified(false);
    }

    public boolean isShowing()   { return stage.isShowing(); }
    public boolean isIconified() { return stage.isIconified(); }
    public void    minimize()    { stage.setIconified(true); }
    public void    front()       { stage.show(); stage.toFront(); stage.setIconified(false); }

    // ── Loading ──────────────────────────────────────────────────────────────--

    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle(QTraceI18n.t("graph.open"));
        try {
            File dir = QTraceConfig.get().getExportDir().toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
        } catch (Exception ignored) {}
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(".qtrace", "*.qtrace"));
        File f = fc.showOpenDialog(stage);
        if (f != null) load(f);
    }

    private void load(File file) {
        nodes.clear();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonObject();
            String imgName = root.has("image") && root.getAsJsonObject("image").has("name")
                ? root.getAsJsonObject("image").get("name").getAsString() : file.getName();
            headerLabel.setText("⑃  " + imgName);

            JsonArray sessions = root.has("sessions") && root.get("sessions").isJsonArray()
                ? root.getAsJsonArray("sessions") : new JsonArray();
            for (int i = 0; i < sessions.size(); i++) {
                nodes.add(parseNode(sessions.get(i).getAsJsonObject(), i));
            }
        } catch (Exception e) {
            headerLabel.setText(QTraceI18n.t("graph.load.error") + " — " + e.getMessage());
        }
        layout();
        redraw(null);
        showEmptyDetail();
    }

    private Node parseNode(JsonObject s, int index) {
        Node n = new Node();
        n.index         = index;
        n.id            = str(s, "session_id", "");
        n.parentId      = str(s, "parent_session_id", null);
        n.branch        = str(s, "branch", "main");
        n.contributor   = str(s, "user", "unknown");
        n.exportedAt    = str(s, "exported_at", "");
        n.stepsCaptured = s.has("steps_captured") ? s.get("steps_captured").getAsInt() : 0;

        if (s.has("steps") && s.get("steps").isJsonArray()) {
            for (JsonElement el : s.getAsJsonArray("steps")) {
                JsonObject st = el.getAsJsonObject();
                if (st.has("pre_tracking") && st.get("pre_tracking").getAsBoolean()) n.preTracking++;
            }
        }
        if (s.has("contributions") && s.get("contributions").isJsonObject())
            n.contributions = s.getAsJsonObject("contributions");

        if (s.has("validation") && s.get("validation").isJsonObject()) {
            JsonObject v = s.getAsJsonObject("validation");
            n.validator  = str(v, "validator", null);
            n.confidence = str(v, "confidence", null);
            n.fidelity   = str(v, "classifier_fidelity", null);
            n.notes      = str(v, "notes", null);
            n.scope      = str(v, "scope", null);
            n.signed     = v.has("validatorKeyPub") && !v.get("validatorKeyPub").getAsString().isBlank();
            String ih    = str(v, "imageHash", null);
            if (ih != null && ih.length() >= 12) n.imageHashShort = ih.substring(0, 12) + "…";
        }
        return n;
    }

    // ── Layout & drawing ─────────────────────────────────────────────────────--

    private void layout() {
        for (Node n : nodes) {
            n.cx = ORIGIN_X + n.index * X_GAP;
            n.cy = LANE_Y;   // v1: single "main" lane
        }
        double width = Math.max(900, ORIGIN_X * 2 + Math.max(0, nodes.size() - 1) * X_GAP);
        canvas.setWidth(width);
    }

    private void redraw(Node selected) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web(BG_BASE));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        if (nodes.isEmpty()) {
            g.setFill(Color.web(TEXT_MUTED));
            g.setFont(Font.font("System", 13));
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(QTraceI18n.t("graph.empty"), 30, LANE_Y);
            return;
        }

        // Edges: parent → child (linear in v1).
        g.setStroke(Color.web(BORDER));
        g.setLineWidth(3);
        for (int i = 1; i < nodes.size(); i++) {
            Node a = nodes.get(i - 1), b = nodes.get(i);
            g.strokeLine(a.cx + NODE_R, a.cy, b.cx - NODE_R, b.cy);
        }

        for (Node n : nodes) drawNode(g, n, n == selected);
    }

    private void drawNode(GraphicsContext g, Node n, boolean selected) {
        Color nodeColor = nodeColor(n);

        if (selected) {
            g.setStroke(Color.web(BLUE));
            g.setLineWidth(3);
            g.strokeOval(n.cx - NODE_R - 4, n.cy - NODE_R - 4, (NODE_R + 4) * 2, (NODE_R + 4) * 2);
        }
        g.setFill(nodeColor);
        g.fillOval(n.cx - NODE_R, n.cy - NODE_R, NODE_R * 2, NODE_R * 2);

        // Commit index inside the node.
        g.setFill(Color.web(BG_BASE));
        g.setFont(Font.font("System", FontWeight.BOLD, 13));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("#" + (n.index + 1), n.cx, n.cy + 4);

        // Contributor badge (initials) above the node — one author per commit.
        double bx = n.cx, by = n.cy - NODE_R - 18;
        double br = 13;
        g.setFill(contributorColor(n.contributor));
        g.fillOval(bx - br, by - br, br * 2, br * 2);
        g.setFill(Color.web(BG_BASE));
        g.setFont(Font.font("System", FontWeight.BOLD, 10));
        g.fillText(initials(n.contributor), bx, by + 3);

        // Contributor name above the badge.
        g.setFill(Color.web(TEXT_SUB));
        g.setFont(Font.font("System", 10));
        g.fillText(ellipsis(n.contributor, 18), n.cx, by - br - 4);

        // Title (notes → scope → Session #n) below the node.
        g.setFill(Color.web(TEXT_MAIN));
        g.setFont(Font.font("System", FontWeight.BOLD, 11));
        g.fillText(ellipsis(title(n), 22), n.cx, n.cy + NODE_R + 18);

        // Validator + signature mark.
        if (n.validator != null) {
            g.setFill(n.signed ? Color.web(GREEN) : Color.web(TEXT_MUTED));
            g.setFont(Font.font("System", 10));
            g.fillText((n.signed ? "✓ " : "") + ellipsis(n.validator, 20), n.cx, n.cy + NODE_R + 34);
        }
        // Date.
        g.setFill(Color.web(TEXT_MUTED));
        g.setFont(Font.font("System", 9));
        g.fillText(dateShort(n.exportedAt), n.cx, n.cy + NODE_R + 48);
    }

    // ── Detail panel ───────────────────────────────────────────────────────────

    private void showEmptyDetail() {
        detailBox.getChildren().setAll(
            sectionTitle(QTraceI18n.t("graph.detail.title")),
            muted(QTraceI18n.t("graph.detail.hint")));
    }

    private void showDetail(Node n) {
        detailBox.getChildren().clear();
        detailBox.getChildren().add(sectionTitle("#" + (n.index + 1) + " — " + title(n)));

        addBadgeRow(n);
        if (n.validator != null)
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.validator"),
                (n.signed ? "✓ " : "") + n.validator));
        if (n.confidence != null)
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.confidence"), n.confidence));
        if (n.fidelity != null)
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.fidelity"), n.fidelity));
        if (n.scope != null && !n.scope.isBlank())
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.scope"), n.scope));
        detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.branch"), n.branch));
        detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.date"), dateShort(n.exportedAt)));
        if (n.imageHashShort != null)
            detailBox.getChildren().add(kv(QTraceI18n.t("graph.detail.imagehash"), n.imageHashShort));

        // "Who did what" — categorized actions of this commit's contributor.
        detailBox.getChildren().add(sectionTitle(QTraceI18n.t("graph.detail.actions")));
        boolean any = false;
        if (n.contributions != null && n.contributions.has("actions")
                && n.contributions.get("actions").isJsonObject()) {
            JsonObject acts = n.contributions.getAsJsonObject("actions");
            for (String k : acts.keySet()) {
                detailBox.getChildren().add(bullet(k + "  ×" + acts.get(k).getAsInt()));
                any = true;
            }
        }
        if (!any) detailBox.getChildren().add(muted(QTraceI18n.t("graph.detail.noactions")));

        if (n.preTracking > 0)
            detailBox.getChildren().add(muted(QTraceI18n.t("graph.detail.inherited")
                + " : " + n.preTracking));

        if (n.notes != null && !n.notes.isBlank()) {
            detailBox.getChildren().add(sectionTitle(QTraceI18n.t("graph.detail.notes")));
            Label notes = new Label(n.notes);
            notes.setWrapText(true);
            notes.setTextFill(Color.web(TEXT_SUB));
            notes.setFont(Font.font("System", 11));
            detailBox.getChildren().add(notes);
        }
    }

    private void addBadgeRow(Node n) {
        Label badge = new Label(initials(n.contributor));
        badge.setMinSize(26, 26);
        badge.setAlignment(Pos.CENTER);
        Color c = contributorColor(n.contributor);
        badge.setStyle("-fx-background-radius: 13; -fx-background-color: " + toHex(c) + ";"
            + "-fx-text-fill: " + BG_BASE + "; -fx-font-weight: bold; -fx-font-size: 10;");
        Label name = new Label(n.contributor);
        name.setTextFill(Color.web(TEXT_MAIN));
        name.setFont(Font.font("System", FontWeight.BOLD, 12));
        HBox row = new HBox(8, badge, name);
        row.setAlignment(Pos.CENTER_LEFT);
        detailBox.getChildren().add(row);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────--

    private Node nodeAt(double x, double y) {
        for (Node n : nodes) {
            double dx = x - n.cx, dy = y - n.cy;
            if (dx * dx + dy * dy <= NODE_R * NODE_R) return n;
        }
        return null;
    }

    private static String title(Node n) {
        // Commit title is the stamp notes, never the scope.
        if (n.notes != null && !n.notes.isBlank()) return n.notes;
        return "Session #" + (n.index + 1);
    }

    private Color nodeColor(Node n) {
        if ("COMPROMISED".equalsIgnoreCase(n.fidelity)) return Color.web(RED);
        if ("DEGRADED".equalsIgnoreCase(n.fidelity))    return Color.web(PEACH);
        if (n.confidence == null)                       return Color.web(TEXT_MUTED);
        return switch (n.confidence.toLowerCase()) {
            case "high"   -> Color.web(GREEN);
            case "medium" -> Color.web(PEACH);
            case "low"    -> Color.web(RED);
            default        -> Color.web(BLUE);
        };
    }

    private static Color contributorColor(String name) {
        if (name == null || name.isBlank()) return Color.web("#6c7086");
        double hue = Math.abs(name.hashCode()) % 360;
        return Color.hsb(hue, 0.55, 0.85);
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(parts[0].charAt(0)));
        if (parts.length > 1) sb.append(Character.toUpperCase(parts[parts.length - 1].charAt(0)));
        return sb.toString();
    }

    private static String dateShort(String iso) {
        if (iso == null || iso.length() < 16) return iso == null ? "" : iso;
        return iso.substring(0, 10) + " " + iso.substring(11, 16);
    }

    private static String ellipsis(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String str(JsonObject o, String key, String def) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : def;
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
            (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    private Label sectionTitle(String t) {
        Label l = new Label(t);
        l.setTextFill(Color.web(BLUE));
        l.setFont(Font.font("System", FontWeight.BOLD, 12));
        l.setPadding(new Insets(8, 0, 2, 0));
        return l;
    }

    private Label kv(String k, String v) {
        Label l = new Label(k + " : " + v);
        l.setTextFill(Color.web(TEXT_SUB));
        l.setFont(Font.font("System", 11));
        l.setWrapText(true);
        return l;
    }

    private Label bullet(String s) {
        Label l = new Label("• " + s);
        l.setTextFill(Color.web(TEXT_MAIN));
        l.setFont(Font.font("System", 11));
        return l;
    }

    private Label muted(String s) {
        Label l = new Label(s);
        l.setTextFill(Color.web(TEXT_MUTED));
        l.setFont(Font.font("System", 11));
        l.setWrapText(true);
        return l;
    }

    private void styleButton(Button b) {
        b.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-text-fill: " + TEXT_MAIN + ";"
            + "-fx-border-color: " + BORDER + "; -fx-border-radius: 4; -fx-background-radius: 4;"
            + "-fx-cursor: hand; -fx-padding: 4 10 4 10;");
    }
}
