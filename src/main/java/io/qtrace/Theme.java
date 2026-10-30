package io.qtrace;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * qTrace's colours, dark (Catppuccin Mocha) or light (Catppuccin Latte), applied to every window.
 * <p>
 * Each colour is a token ("bg-base", "text-main"...). A stylesheet defines the token as a looked-up
 * colour ({@code -qt-bg-base}) on every scene's root, so inline styles written as
 * {@code "-fx-background-color: " + Theme.v("bg-base")} follow the theme, windows already open included.
 * {@link #hex(String)} gives the current value for the places that need a literal (icons, Canvas).
 */
public final class Theme {

    public enum Mode {
        DARK, LIGHT, AUTO;

        public static Mode parse(String s) {
            if (s != null) for (Mode m : values()) if (m.name().equalsIgnoreCase(s.trim())) return m;
            return DARK;
        }
        public String id() { return name().toLowerCase(); }
    }

    public static final List<String> TOKENS = List.of(
        "bg-base", "bg-surface", "bg-elevated", "bg-card", "bg-row", "border",
        "text-main", "text-sub", "text-muted", "text-faint",
        "blue", "green", "peach", "red", "gold", "mauve", "sky", "lavender", "yellow",
        "cta-teal", "cta-teal-soft", "cta-teal-border",
        "group-workspace", "group-tools", "group-admin",
        "row-alt", "blue-soft", "red-soft", "chip", "nav-selected", "green-soft", "gold-bg", "gold-border", "btn", "btn-hover", "teal", "red-strong", "glass94", "glass85", "peach-soft", "peach-faint", "red-faint", "gold-soft", "gold-line", "green-faint", "field");

    public static final Map<String, String> DARK = palette(
        "#1e1e2e", "#181825", "#26263a", "#24273a", "#2a2b3d", "#313244",
        "#cdd6f4", "#a6adc8", "#6c7086", "#4a4a5e",
        "#89b4fa", "#a6e3a1", "#fab387", "#f38ba8", "#d9b34d", "#cba6f7", "#74c7ec", "#b4befe", "#f9e2af",
        "#2dd4bf", "rgba(45,212,191,0.14)", "rgba(45,212,191,0.35)",
        "#8B5CF6", "#10B981", "#9CA3AF",
        "#20202e", "#20304a", "#3a2020", "#2a2a3a", "#242438", "#28352c", "#0e0c08", "#4a3c1e", "#45475a", "#585b70", "#94e2d5", "#c0392b", "rgba(30,30,46,0.94)", "rgba(30,30,46,0.85)", "rgba(250,179,135,0.12)", "rgba(250,179,135,0.10)", "rgba(243,139,168,0.10)", "rgba(217,179,77,0.12)", "rgba(217,179,77,0.45)", "rgba(166,227,161,0.14)", "#181825");

    /** Catppuccin Latte, with the accents darkened where Latte's own would fall under 3:1 on its base. */
    public static final Map<String, String> LIGHT = palette(
        "#eff1f5", "#e6e9ef", "#ffffff", "#f5f6f9", "#e4e7ee", "#ccd0da",
        "#4c4f69", "#5c5f77", "#7c7f93", "#acb0be",
        "#1e66f5", "#2f8a1e", "#d4560a", "#d20f39", "#9a7410", "#8839ef", "#0a86b8", "#7287fd", "#b87400",
        "#0d9488", "rgba(13,148,136,0.12)", "rgba(13,148,136,0.35)",
        "#7c3aed", "#059669", "#6b7280",
        "#e8ebf1", "#d6e2fc", "#f9dce2", "#dfe3ec", "#dde1ea", "#dcefd6", "#f6edd2", "#d8c27a", "#ccd0da", "#bcc0cc", "#179299", "#b8102f", "rgba(239,241,245,0.94)", "rgba(239,241,245,0.85)", "rgba(212,86,10,0.12)", "rgba(212,86,10,0.10)", "rgba(210,15,57,0.10)", "rgba(154,116,16,0.12)", "rgba(154,116,16,0.45)", "rgba(47,138,30,0.14)", "#ffffff");

    private static final ObjectProperty<Map<String, String>> CURRENT = new SimpleObjectProperty<>(DARK);
    private static Mode mode = Mode.DARK;
    private static boolean installed;

    private Theme() {}

    private static Map<String, String> palette(String... values) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < TOKENS.size(); i++) m.put(TOKENS.get(i), values[i]);
        return Map.copyOf(m);
    }

    // ── Pure logic ───────────────────────────────────────────────────────────

    /** @param quPathDark whether QuPath itself is dark; null when unknown (then dark) */
    static Map<String, String> resolve(Mode mode, Boolean quPathDark) {
        return switch (mode) {
            case DARK -> DARK;
            case LIGHT -> LIGHT;
            case AUTO -> Boolean.FALSE.equals(quPathDark) ? LIGHT : DARK;
        };
    }

    /** The stylesheet that defines every token as a looked-up colour, and JavaFX's own base colours. */
    static String css(Map<String, String> p) {
        StringBuilder sb = new StringBuilder("/* " + MARK + " */\n.root {\n");
        for (String t : TOKENS) sb.append("  -qt-").append(t).append(": ").append(p.get(t)).append(";\n");
        for (String t : TOKENS) {
            String c = p.get(t);
            if (!c.startsWith("#")) continue;   // already translucent
            int r = Integer.parseInt(c.substring(1, 3), 16), g = Integer.parseInt(c.substring(3, 5), 16),
                b = Integer.parseInt(c.substring(5, 7), 16);
            for (int a : ALPHAS) {
                sb.append("  -qt-").append(t).append("-a").append(a).append(": rgba(")
                  .append(r).append(',').append(g).append(',').append(b).append(",0.")
                  .append(String.format("%02d", a)).append(");\n");
            }
        }
        sb.append("  -fx-base: ").append(p.get("bg-base")).append(";\n");
        sb.append("  -fx-control-inner-background: ").append(p.get("field")).append(";\n");
        sb.append("  -fx-accent: ").append(p.get("blue")).append(";\n");
        sb.append("  -fx-text-base-color: ").append(p.get("text-main")).append(";\n}\n");
        // A dialog's default button (Yes, Install...): Modena paints it a pale accent with white text,
        // unreadable on the dark theme — QuPath's own "Save changes" included. Solid accent, base-coloured text.
        sb.append(".button:default, .button:default:hover, .button:default:focused {\n")
          .append("  -fx-background-color: ").append(p.get("blue")).append(";\n")
          .append("  -fx-text-fill: ").append(p.get("bg-base")).append(";\n}\n");
        return sb.toString();
    }

    /** WCAG contrast ratio of two #rrggbb colours. */
    static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    // ── Colour objects that follow the theme ─────────────────────────────────

    private static final java.util.WeakHashMap<Object, Object[]> TRACKED = new java.util.WeakHashMap<>();

    /** A colour reference is a looked-up name ({@code -qt-text-main}) or a literal ({@code #123456}). */
    public static javafx.scene.paint.Color web(String ref) {
        if (ref != null && ref.startsWith("-qt-")) {
            String hex = CURRENT.get().get(ref.substring(4));
            if (hex != null) return javafx.scene.paint.Color.web(hex);
        }
        return javafx.scene.paint.Color.web(ref);
    }

    /** Sets the text colour now and again whenever the theme changes. */
    public static void text(javafx.scene.control.Labeled node, String ref) {
        node.setTextFill(web(ref));
        TRACKED.put(node, new Object[] {"text", ref});
    }

    public static void fill(javafx.scene.shape.Shape node, String ref) {
        node.setFill(web(ref));
        TRACKED.put(node, new Object[] {"fill", ref});
    }

    public static void stroke(javafx.scene.shape.Shape node, String ref) {
        node.setStroke(web(ref));
        TRACKED.put(node, new Object[] {"stroke", ref});
    }

    private static void repaintTracked() {
        for (var e : new java.util.ArrayList<>(TRACKED.entrySet())) {
            Object n = e.getKey();
            String kind = (String) e.getValue()[0], ref = (String) e.getValue()[1];
            if (n instanceof javafx.scene.control.Labeled l && kind.equals("text")) l.setTextFill(web(ref));
            else if (n instanceof javafx.scene.shape.Shape sh && kind.equals("fill")) sh.setFill(web(ref));
            else if (n instanceof javafx.scene.shape.Shape sh && kind.equals("stroke")) sh.setStroke(web(ref));
        }
    }

    // ── Use ──────────────────────────────────────────────────────────────────

    /** The looked-up colour name of a token, for inline styles: {@code "-qt-bg-base"}. */
    public static String v(String token) { return "-qt-" + token; }

    /** The shades {@link #alpha} offers, in percent: a tint, a soft line, a border. */
    static final int[] ALPHAS = {13, 27, 40};

    /**
     * A looked-up colour's translucent shade, for inline styles: {@code alpha(v("blue"), 40)} is
     * {@code "-qt-blue-a40"}, the blue at 40 % that the stylesheet defines in each palette.
     * A looked-up name cannot take a hex alpha suffix ({@code "-qt-blue" + "22"} is no colour).
     */
    public static String alpha(String lookedUp, int percent) {
        if (java.util.Arrays.stream(ALPHAS).noneMatch(a -> a == percent))
            throw new IllegalArgumentException("no " + percent + " % shade; offered: " + java.util.Arrays.toString(ALPHAS));
        return lookedUp + "-a" + percent;
    }

    /** The token's current value as a literal, for the code that cannot use a looked-up colour. */
    public static String hex(String token) { return CURRENT.get().get(token); }

    public static ObjectProperty<Map<String, String>> currentProperty() { return CURRENT; }
    public static boolean isLight() { return CURRENT.get() == LIGHT; }
    public static Mode mode() { return mode; }

    /**
     * Chooses the mode and repaints every open window. Call on the FX thread.
     */
    public static void setMode(Mode m) {
        mode = m == null ? Mode.DARK : m;
        refresh();
    }

    /** Starts painting every window that opens, now and later; idempotent. FX thread. */
    public static void install(Mode initial) {
        mode = initial == null ? Mode.DARK : initial;
        if (!installed) {
            installed = true;
            Window.getWindows().addListener((ListChangeListener<Window>) c -> {
                while (c.next()) if (c.wasAdded()) for (Window w : c.getAddedSubList()) track(w);
            });
            for (Window w : Window.getWindows()) track(w);
            watchQuPath();
        }
        refresh();
    }

    private static void track(Window w) {
        apply(w.getScene());
        w.sceneProperty().addListener((o, a, b) -> apply(b));
    }

    /** Paints one scene; windows are painted on their own, this is for a scene snapshotted without one. FX thread. */
    public static void apply(Scene scene) {
        if (scene == null) return;
        scene.getStylesheets().removeIf(Theme::isOurs);
        scene.getStylesheets().add(0, sheet());
    }

    private static final String MARK = "qtrace-theme-sheet";

    static boolean isOurs(String s) { return s.startsWith("data:text/css,") && s.contains(MARK); }

    private static String sheet() {
        String enc = java.net.URLEncoder.encode(css(CURRENT.get()), StandardCharsets.UTF_8).replace("+", "%20");
        return "data:text/css," + enc;
    }

    private static void refresh() {
        CURRENT.set(resolve(mode, quPathDark()));
        for (Window w : Window.getWindows()) apply(w.getScene());
        repaintTracked();
    }

    // ── QuPath's own theme (API absent on 0.5.x: then dark) ──────────────────

    private static Boolean quPathDark() {
        try {
            Class<?> c = Class.forName("qupath.lib.gui.prefs.QuPathStyleManager");
            Object scheme = c.getMethod("getStyleColorScheme").invoke(null);
            return scheme == null ? null : !"LIGHT".equals(String.valueOf(scheme));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void watchQuPath() {
        try {
            Class<?> c = Class.forName("qupath.lib.gui.prefs.QuPathStyleManager");
            Object prop = c.getMethod("selectedStyleProperty").invoke(null);
            ((javafx.beans.value.ObservableValue<?>) prop).addListener((o, a, b) -> {
                if (mode == Mode.AUTO) Platform.runLater(Theme::refresh);
            });
        } catch (Throwable ignored) {}
    }
}
