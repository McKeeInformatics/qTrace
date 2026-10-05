package io.qtrace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What modules add to the panel: entries that unfold from one of its buttons ({@link
 * PanelFlyout}) — the cohort map from Dashboard, the workflow editor from Version. A module
 * registers its entry when it is installed; the panel lists those of the modules the licence
 * includes ({@link ModuleEntitlements}) each time a button is pointed at. With no module
 * registered the panel is unchanged.
 */
public final class PanelExtensions {

    /** The panel buttons an entry can unfold from. */
    public static final String DASHBOARD = "dashboard";
    public static final String VERSIONS  = "versions";

    /** @param module the registering module's Qtrace-Module name, e.g. "cohort" */
    public record Entry(String module, String anchor, String label, Runnable action) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private PanelExtensions() {}

    /**
     * @param anchor {@link #DASHBOARD} or {@link #VERSIONS}
     * @param action run on the FX thread when the entry is clicked
     */
    public static void register(String module, String anchor, String label, Runnable action) {
        if (module == null || anchor == null || label == null || action == null) return;
        synchronized (ENTRIES) {
            ENTRIES.removeIf(e -> e.module().equals(module) && e.anchor().equals(anchor) && e.label().equals(label));
            ENTRIES.add(new Entry(module, anchor, label, action));
        }
        for (Runnable l : LISTENERS) l.run();
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

    /** Called (on the registering thread) each time an entry is registered: a button may now have something to unfold. */
    static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    static void clear() {
        synchronized (ENTRIES) { ENTRIES.clear(); }
        LISTENERS.clear();
    }
}
