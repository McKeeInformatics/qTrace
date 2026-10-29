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

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The full panel's mini-panel mode (the blue gear): the panel's tools are clicked to be in the
 * mini-panel (green) or not, and a column beside the panel shows the mini-panel as it will be,
 * its blocks dragged up or down. Every change is saved at once ({@link MiniPanelChoice}).
 *
 * The panel tags each of its tool buttons with {@link #TOOL} (the tool's name) and
 * {@link #ICON} (its icon, by colour); this mode works from those alone.
 */
final class MiniPanelEditor {

    static final String TOOL = "qtrace.tool";
    static final String ICON = "qtrace.icon";
    /** Set on a tool button while this mode paints it: its own hover look stands aside. */
    static final String EDITING = "qtrace.mini.editing";
    private static final String BASE_STYLE = "qtrace.mini.base";

    private static final double ICON_SIZE = 26;

    private final Stage stage;
    private final Parent toolbar;
    private final Runnable redrawToolbar;
    private final HBox banner;
    private final VBox side, column;
    private final EventHandler<ActionEvent> clicks = this::onToolClicked;
    private final javafx.beans.property.BooleanProperty active = new javafx.beans.property.SimpleBooleanProperty();
    private List<String> tools = List.of();

    /**
     * @param toolbar       the panel's tool buttons, at any depth
     * @param redrawToolbar draws the toolbar again, with the buttons' own states
     */
    MiniPanelEditor(Stage stage, Parent toolbar, Runnable redrawToolbar) {
        this.stage = stage;
        this.toolbar = toolbar;
        this.redrawToolbar = redrawToolbar;

        Label hint = new Label(QTraceI18n.t("minipanel.config.hint"));
        hint.setWrapText(true);
        hint.setFont(Font.font("System", 11));
        Theme.text(hint, QTracePanel.TEXT_SUB);
        HBox.setHgrow(hint, Priority.ALWAYS);
        hint.setMaxWidth(Double.MAX_VALUE);
        Button reset = new Button(QTraceI18n.t("minipanel.config.default"));
        reset.setStyle(linkStyle());
        reset.setOnAction(e -> {
            MiniPanelChoice.set(null); // back to qTrace's own mini-panel
            tools = QTraceMiniPanel.defaultTools();
            repaint();
        });
        Button done = new Button(QTraceI18n.t("minipanel.config.done"));
        done.setStyle("-fx-background-color: " + QTracePanel.BLUE + "; -fx-text-fill: " + QTracePanel.BG_BASE + ";"
            + "-fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 3 10 3 10;");
        done.setOnAction(e -> exit());
        // The words wrap; the two buttons keep their whole name.
        reset.setMinWidth(Region.USE_PREF_SIZE);
        done.setMinWidth(Region.USE_PREF_SIZE);
        banner = new HBox(10, hint, reset, done);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(7, 8, 7, 10));
        banner.setStyle("-fx-background-color: " + Theme.alpha(QTracePanel.BLUE, 13) + "; -fx-background-radius: 8;"
            + "-fx-border-color: " + Theme.alpha(QTracePanel.BLUE, 40) + "; -fx-border-radius: 8;");
        show(banner, false);

        // The mini-panel as it will look: same glass, same width, same icon size.
        column = new VBox(3);
        column.setAlignment(Pos.TOP_CENTER);
        column.setPadding(new Insets(6, 5, 6, 5));
        column.setStyle("-fx-background-color: -qt-glass94; -fx-background-radius: 10;"
            + "-fx-border-color: " + Theme.alpha(QTracePanel.BLUE, 40) + "; -fx-border-radius: 10;");
        column.setMinWidth(ICON_SIZE + 22);
        Label title = new Label(QTraceI18n.t("minipanel.config.column"));
        title.setFont(Font.font("System", FontWeight.BOLD, 10));
        Theme.text(title, QTracePanel.BLUE);
        title.setMinWidth(Region.USE_PREF_SIZE);
        side = new VBox(6, title, column);
        side.setAlignment(Pos.TOP_CENTER);
        side.setPadding(new Insets(16, 14, 16, 0));
        show(side, false);
    }

    /** The strip under the toolbar that says what this mode does. */
    Node banner() { return banner; }

    /** The mini-panel's column, beside the panel. */
    Node side() { return side; }

    boolean active() { return active.get(); }

    /** True while the mode is on — the gear that turns it on shows it. */
    javafx.beans.property.ReadOnlyBooleanProperty activeProperty() { return active; }

    void toggle() {
        if (active.get()) exit();
        else enter();
    }

    void enter() {
        if (active.get()) return;
        active.set(true);
        List<String> chosen = MiniPanelChoice.tools();
        tools = chosen != null ? chosen : QTraceMiniPanel.defaultTools();
        show(banner, true);
        show(side, true);
        toolbar.addEventFilter(ActionEvent.ACTION, clicks);
        stage.setWidth(stage.getWidth() + sideWidth());
        repaint();
    }

    void exit() {
        if (!active.get()) return;
        active.set(false);
        toolbar.removeEventFilter(ActionEvent.ACTION, clicks);
        stage.setWidth(Math.max(stage.getMinWidth(), stage.getWidth() - sideWidth()));
        show(banner, false);
        show(side, false);
        redrawToolbar.run(); // the buttons get their own looks and states back
    }

    /** The toolbar was drawn again (a module came in): its new buttons are painted for this mode. */
    void toolbarRedrawn() {
        if (active.get()) repaint();
    }

    // ── Clicks ───────────────────────────────────────────────────────────────

    private void onToolClicked(ActionEvent e) {
        String tool = toolOf(e.getTarget() instanceof Node n ? n : null);
        if (tool == null) return;
        e.consume(); // in this mode a click chooses the tool, it does not run it
        tools = MiniPanelChoice.toggle(tools, tool);
        MiniPanelChoice.set(tools);
        repaint();
    }

    private static String toolOf(Node n) {
        for (; n != null; n = n.getParent())
            if (n.getProperties().get(TOOL) instanceof String t) return t;
        return null;
    }

    // ── Painting ─────────────────────────────────────────────────────────────

    private void repaint() {
        List<Button> buttons = new ArrayList<>();
        collect(toolbar, buttons);
        for (Button b : buttons) paint(b, tools.contains((String) b.getProperties().get(TOOL)));
        drawColumn(buttons);
    }

    private static void collect(Node n, List<Button> out) {
        if (n instanceof Button b && b.getProperties().get(TOOL) != null) out.add(b);
        if (n instanceof Parent p) for (Node c : p.getChildrenUnmodifiable()) collect(c, out);
    }

    @SuppressWarnings("unchecked")
    private static void paint(Button b, boolean on) {
        var props = b.getProperties();
        props.put(EDITING, true);
        props.putIfAbsent(BASE_STYLE, b.getStyle());
        // Greyed tools (Reset with no image, Stamp with nothing captured) can be chosen all the same.
        b.setDisable(false);
        b.setOpacity(1.0);
        String color = on ? QTracePanel.GREEN : QTracePanel.TEXT_MUTED;
        if (props.get(ICON) instanceof Function<?, ?> icon) b.setGraphic(((Function<Color, Node>) icon).apply(Theme.web(color)));
        Theme.text(b, on ? QTracePanel.GREEN : QTracePanel.TEXT_MUTED);
        b.setStyle(props.get(BASE_STYLE) + (on
            ? "-fx-background-color: " + Theme.alpha(QTracePanel.GREEN, 13) + "; -fx-background-radius: 7;"
              + "-fx-border-color: " + QTracePanel.GREEN + "; -fx-border-width: 1.5; -fx-border-radius: 7;"
            : "-fx-border-color: " + QTracePanel.BORDER + "; -fx-border-style: segments(3, 3); -fx-border-radius: 7;"));
    }

    @SuppressWarnings("unchecked")
    private void drawColumn(List<Button> buttons) {
        List<Node> nodes = new ArrayList<>();
        Circle dot = new Circle(6, Theme.web(QTracePanel.RED));
        StackPane status = fixed(dot);
        status.setPadding(new Insets(3, 0, 9, 0));
        nodes.add(status);

        List<String> shown = new ArrayList<>();
        for (String t : tools) if (buttons.stream().anyMatch(b -> t.equals(b.getProperties().get(TOOL)))) shown.add(t);
        if (shown.isEmpty()) {
            Label empty = new Label(QTraceI18n.t("minipanel.config.empty"));
            empty.setWrapText(true);
            empty.setMaxWidth(ICON_SIZE + 6);
            empty.setFont(Font.font("System", 9));
            Theme.text(empty, QTracePanel.TEXT_MUTED);
            empty.setStyle("-fx-border-color: " + QTracePanel.BORDER + "; -fx-border-style: segments(3, 3);"
                + "-fx-border-radius: 6; -fx-padding: 6 2 6 2; -fx-alignment: center; -fx-text-alignment: center;");
            nodes.add(empty);
        }
        for (String t : shown) {
            Button b = buttons.stream().filter(x -> t.equals(x.getProperties().get(TOOL))).findFirst().orElseThrow();
            Function<Color, Node> icon = b.getProperties().get(ICON) instanceof Function<?, ?> f
                ? (Function<Color, Node>) f : c -> new Circle(6, c);
            nodes.add(block(t, icon, b.getTooltip() != null ? b.getTooltip().getText() : t));
        }

        Region line = new Region();
        line.setPrefHeight(1);
        line.setMaxWidth(ICON_SIZE);
        line.setStyle("-fx-background-color: " + QTracePanel.BORDER + ";");
        VBox.setMargin(line, new Insets(5, 0, 5, 0));
        nodes.add(line);
        nodes.add(fixed(glyph("⚙")));
        nodes.add(fixed(QTraceMiniPanel.reportIcon(Theme.web(QTracePanel.TEXT_MUTED))));
        Label expand = new Label("⤢");
        expand.setStyle("-fx-border-color: " + QTracePanel.BORDER + "; -fx-border-radius: 4; -fx-padding: 0 6 0 6;");
        Theme.text(expand, QTracePanel.TEXT_MAIN);
        StackPane exp = fixed(expand);
        VBox.setMargin(exp, new Insets(4, 0, 0, 0));
        nodes.add(exp);
        column.getChildren().setAll(nodes);
    }

    /** A chosen tool in the column: dragged up or down to take another place. */
    private StackPane block(String tool, Function<Color, Node> icon, String name) {
        Node drawn = icon.apply(Theme.web(QTracePanel.TEXT_MAIN));
        drawn.setScaleX(ICON_SIZE / 18);
        drawn.setScaleY(ICON_SIZE / 18);
        StackPane block = new StackPane(drawn);
        block.setPrefSize(ICON_SIZE + 8, ICON_SIZE + 6);
        block.setMaxSize(ICON_SIZE + 8, ICON_SIZE + 6);
        block.setCursor(Cursor.OPEN_HAND);
        String idle = "-fx-background-radius: 6; -fx-background-color: transparent;";
        String hover = "-fx-background-radius: 6; -fx-background-color: " + QTracePanel.BG_ELEVATED + ";";
        String lifted = hover + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 8, 0, 0, 2);";
        block.setStyle(idle);
        Tooltip.install(block, new Tooltip(name + " · " + QTraceI18n.t("minipanel.config.drag")));
        block.setOnMouseEntered(e -> { if (!e.isPrimaryButtonDown()) block.setStyle(hover); });
        block.setOnMouseExited(e -> { if (!e.isPrimaryButtonDown()) block.setStyle(idle); });
        double[] start = new double[1];
        block.setOnMousePressed(e -> {
            start[0] = e.getSceneY();
            block.setCursor(Cursor.CLOSED_HAND);
            block.setStyle(lifted);
            block.toFront();
        });
        block.setOnMouseDragged(e -> block.setTranslateY(e.getSceneY() - start[0]));
        block.setOnMouseReleased(e -> {
            int from = tools.indexOf(tool);
            double pitch = block.getHeight() + column.getSpacing();
            int to = Math.max(0, Math.min(tools.size() - 1, from + (int) Math.round(block.getTranslateY() / pitch)));
            block.setTranslateY(0);
            block.setCursor(Cursor.OPEN_HAND);
            block.setStyle(idle);
            if (to != from) {
                tools = MiniPanelChoice.move(tools, from, to);
                MiniPanelChoice.set(tools);
            }
            repaint();
        });
        return block;
    }

    /** What the user cannot take out of the mini-panel: shown, faded. */
    private static StackPane fixed(Node n) {
        StackPane p = new StackPane(n);
        p.setOpacity(0.4);
        Tooltip.install(p, new Tooltip(QTraceI18n.t("minipanel.config.fixed")));
        return p;
    }

    private static Label glyph(String g) {
        Label l = new Label(g);
        l.setFont(Font.font("System", FontWeight.BOLD, 22));
        Theme.text(l, QTracePanel.TEXT_MUTED);
        return l;
    }

    private double sideWidth() {
        side.applyCss();
        double w = side.prefWidth(-1);
        return w > 0 ? w : ICON_SIZE + 36;
    }

    private static String linkStyle() {
        return "-fx-background-color: transparent; -fx-text-fill: " + QTracePanel.BLUE + "; -fx-underline: true;"
            + "-fx-cursor: hand; -fx-padding: 3 4 3 4;";
    }

    private static void show(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }
}
