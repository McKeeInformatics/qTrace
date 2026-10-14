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

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.stage.Window;

import java.util.Map;

/**
 * The green square of Getting started's tour: while a slide talks about a tool, its button is
 * outlined in the panel and in the mini-panel, and every other button is greyed — so the user
 * sees where the button is. {@code set("")} gives the panel back. With the {@link #ONLY} prefix the others stay as they are.
 *
 * <p>A button is known by its tool key ({@code stamp}, {@code upload}, {@code player},
 * {@code versiongraph}, {@code report}, {@code dashboard}, {@code import}, {@code reset},
 * {@code settings}, {@code issue}, {@code expand}, {@code start}, or a module's name): the
 * {@link #KEY} property the mini-panel sets, or the id the full panel already gives its
 * buttons. A tool whose button this workstation does not have simply is not outlined.
 */
public final class TourHighlight {

    /** Node property naming the tool a button opens. */
    public static final String KEY = "qtrace.tool";

    private static final String BASE_STYLE = "qtrace.tour.style";
    private static final String BASE_OPACITY = "qtrace.tour.opacity";
    private static final String ON_STYLE = "-fx-border-color: #a6e3a1; -fx-border-width: 2; -fx-border-radius: 6;"
        + " -fx-background-color: rgba(166,227,161,0.14); -fx-background-radius: 6;";
    private static final double OFF_OPACITY = 0.3;

    /** The full panel's buttons already carry these ids (screenshot harness). */
    private static final Map<String, String> PANEL_IDS = Map.of(
        "stamp-button", "stamp", "upload-button", "upload", "report-button", "report",
        "dashboard-button", "dashboard", "import-button", "import", "reset-button", "reset",
        "replay-button", "player", "versions-button", "versiongraph", "settings-button", "settings",
        "to-mini-panel-button", "expand");

    /** What a button looks like for the tool being shown. */
    enum Look { PLAIN, ON, OFF }

    private static volatile String current = "";
    private static volatile boolean dimOthers = true;

    /** True while a square is shown: the gold squares of unread welcomes step aside. FX thread. */
    private static final BooleanProperty ACTIVE = new SimpleBooleanProperty(false);

    /** Prefix of a key that outlines the button without greying the others (a tool's own welcome). */
    public static final String ONLY = "only:";

    public static BooleanProperty activeProperty() { return ACTIVE; }

    private TourHighlight() {}

    /** Look of the button of tool {@code key} while {@code shown} is the tool the tour talks about. */
    static Look look(String shown, String key) {
        return look(shown, key, true);
    }

    static Look look(String shown, String key, boolean dim) {
        if (shown == null || shown.isEmpty()) return Look.PLAIN;
        if (shown.equals(key)) return Look.ON;
        return dim ? Look.OFF : Look.PLAIN;
    }

    /** Marks a button as the one of this tool. Returns it, for chaining. */
    public static <T extends Node> T tag(T button, String key) {
        button.getProperties().put(KEY, key);
        return button;
    }

    /** Outlines the button of this tool everywhere it shows; "" restores all. Any thread. */
    public static void set(String key) {
        String k = key == null ? "" : key;
        dimOthers = !k.startsWith(ONLY);
        current = dimOthers ? k : k.substring(ONLY.length());
        Platform.runLater(() -> { ACTIVE.set(!current.isEmpty()); applyAll(); });
    }

    /** For a panel that was just rebuilt: gives its new buttons the current look. FX thread. */
    public static void reapply() {
        if (!current.isEmpty()) applyAll();
    }

    private static void applyAll() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null) walk(w.getScene().getRoot());
        }
    }

    private static void walk(Node n) {
        String key = keyOf(n);
        if (key != null) {
            paint(n, look(current, key, dimOthers));
            return;
        }
        if (n instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) walk(child);
        }
    }

    private static String keyOf(Node n) {
        Object k = n.getProperties().get(KEY);
        if (k instanceof String s) return s;
        return n.getId() == null ? null : PANEL_IDS.get(n.getId());
    }

    private static void paint(Node n, Look look) {
        Map<Object, Object> props = n.getProperties();
        if (!props.containsKey(BASE_STYLE)) {
            if (look == Look.PLAIN) return; // never touched
            props.put(BASE_STYLE, n.getStyle() == null ? "" : n.getStyle());
            props.put(BASE_OPACITY, n.getOpacity());
        }
        String style = (String) props.get(BASE_STYLE);
        double opacity = (Double) props.get(BASE_OPACITY);
        switch (look) {
            case ON -> { n.setStyle(style + ON_STYLE); n.setOpacity(1.0); }
            case OFF -> { n.setStyle(style); n.setOpacity(Math.min(opacity, OFF_OPACITY)); }
            case PLAIN -> {
                n.setStyle(style);
                n.setOpacity(opacity);
                props.remove(BASE_STYLE);
                props.remove(BASE_OPACITY);
            }
        }
    }
}
