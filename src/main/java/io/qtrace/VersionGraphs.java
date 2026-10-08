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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Version window a module brings ({@link VersionGraph}). The module registers it when it
 * is installed; Core opens it from the panel, the mini-panel and for the modules that edit or
 * read a workflow. With no module registered there is no Version window, and nothing that
 * opens one is shown.
 */
public final class VersionGraphs {

    private static final Map<String, VersionGraph> GRAPHS = new LinkedHashMap<>();
    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private VersionGraphs() {}

    /** @param module the registering module's Qtrace-Module name, e.g. "versiongraph" — one each */
    public static void register(String module, VersionGraph graph) {
        if (module == null || graph == null) return;
        synchronized (GRAPHS) {
            GRAPHS.remove(module);
            GRAPHS.put(module, graph);
        }
        for (Runnable l : LISTENERS.values()) l.run();
    }

    /** The first registered Version window of a module this workstation is served; null when there is none. */
    public static VersionGraph entitled() {
        synchronized (GRAPHS) {
            for (Map.Entry<String, VersionGraph> e : GRAPHS.entrySet())
                if (ModuleEntitlements.isEntitled(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * Called (on the registering thread) each time a module registers — it can load after the
     * panel was drawn. One listener per {@code owner}: registering again replaces it.
     */
    public static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    static void clear() {
        synchronized (GRAPHS) { GRAPHS.clear(); }
        LISTENERS.clear();
    }
}
