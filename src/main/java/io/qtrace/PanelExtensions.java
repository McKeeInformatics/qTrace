package io.qtrace;

import javafx.scene.Group;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What modules add to the panel: a button of their own, placed right after the one it goes
 * with — the cohort map after Dashboard, the workflow editor after Version (under it on the
 * mini-panel's column). A module registers its button when it is installed; the panel shows
 * those of the modules the licence includes ({@link ModuleEntitlements}). With no module
 * registered the panel is unchanged.
 *
 * <p>When the organization chose its panel ({@link PanelProfile}), a module's button stands
 * where the profile puts the module, whatever it goes with — and a module with no button in
 * qTrace's own panel can bring one for that case ({@link #PROFILE}).
 */
public final class PanelExtensions {

    /** The panel buttons a module's button can follow. */
    public static final String DASHBOARD = "dashboard";
    public static final String VERSIONS  = "versions";
    /**
     * A button of its own, shown only in an organization's panel that names the module — for a
     * tool reached another way in qTrace's own panel (Training, from the player's Open).
     */
    public static final String PROFILE   = "profile";

    /**
     * @param module the registering module's Qtrace-Module name, e.g. "cohort"
     * @param icon   the button's icon drawn in a 24-unit box with the given colour, like the
     *               panel's own; null for a plain mark
     * @param action run on the FX thread at each click. Panel buttons are toggles: the action
     *               closes what it opened when it is already up
     */
    public record Entry(String module, String anchor, String label, Function<Color, Group> icon, Runnable action) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private PanelExtensions() {}

    /** @param anchor {@link #DASHBOARD}, {@link #VERSIONS} or {@link #PROFILE} */
    public static void register(String module, String anchor, String label, Function<Color, Group> icon, Runnable action) {
        if (module == null || anchor == null || label == null || action == null) return;
        synchronized (ENTRIES) {
            ENTRIES.removeIf(e -> e.module().equals(module) && e.anchor().equals(anchor) && e.label().equals(label));
            ENTRIES.add(new Entry(module, anchor, label, icon, action));
        }
        for (Runnable l : LISTENERS.values()) l.run();
    }

    /** Without an icon — what the modules published against Core 1.2.5 call. */
    public static void register(String module, String anchor, String label, Runnable action) {
        register(module, anchor, label, null, action);
    }

    /** The entries of one button, in registration order, without the modules the licence does not include. */
    public static List<Entry> entitled(String anchor) {
        List<Entry> out = new ArrayList<>();
        synchronized (ENTRIES) {
            for (Entry e : ENTRIES)
                if (e.anchor().equals(anchor) && ModuleEntitlements.isEntitled(e.module())) out.add(e);
        }
        return out;
    }

    /** The buttons of one module, in registration order; none when the licence does not include it. */
    public static List<Entry> ofModule(String module) {
        List<Entry> out = new ArrayList<>();
        if (!ModuleEntitlements.isEntitled(module)) return out;
        synchronized (ENTRIES) {
            for (Entry e : ENTRIES) if (e.module().equals(module)) out.add(e);
        }
        return out;
    }

    /**
     * Called (on the registering thread) each time a button is registered — a module can load
     * after the panel was drawn. One listener per {@code owner}: registering again replaces it.
     */
    static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    static void clear() {
        synchronized (ENTRIES) { ENTRIES.clear(); }
        LISTENERS.clear();
    }
}
