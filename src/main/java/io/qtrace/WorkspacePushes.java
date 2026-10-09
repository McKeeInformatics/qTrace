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
 * The workspace upload a module brings ({@link WorkspacePush}). The module registers it when it
 * is installed; Core shows Upload (panel, mini-panel) only once one is registered, and sends
 * through it. With no module registered there is no Upload.
 */
public final class WorkspacePushes {

    private static final Map<String, WorkspacePush> PUSHES = new LinkedHashMap<>();
    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private WorkspacePushes() {}

    /** @param module the registering module's Qtrace-Module name, e.g. "upload" — one each */
    public static void register(String module, WorkspacePush push) {
        if (module == null || push == null) return;
        synchronized (PUSHES) {
            PUSHES.remove(module);
            PUSHES.put(module, push);
        }
        for (Runnable l : LISTENERS.values()) l.run();
    }

    /** The first registered upload of a module this workstation is served; null when there is none. */
    public static WorkspacePush entitled() {
        synchronized (PUSHES) {
            for (Map.Entry<String, WorkspacePush> e : PUSHES.entrySet())
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
        synchronized (PUSHES) { PUSHES.clear(); }
        LISTENERS.clear();
    }
}
