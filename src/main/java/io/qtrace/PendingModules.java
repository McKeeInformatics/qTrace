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

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The modules that arrived on this workstation but do not run yet — installed on disk, served to
 * it, and waiting for QuPath to be restarted. The panels draw their button greyed, so that what
 * Getting started presented is already there and says it is coming.
 */
public final class PendingModules {

    private static volatile Set<String> installed;
    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private PendingModules() {}

    /** True when {@code module} is on disk and this workstation is served it (the caller knows it is not loaded). */
    public static boolean awaiting(String module) {
        return ModuleEntitlements.isEntitled(module) && installed().contains(module);
    }

    /** A module was just installed: the panels draw its button. Any thread. */
    public static void refresh() {
        installed = null;
        for (Runnable l : LISTENERS.values()) l.run();
    }

    /** One listener per {@code owner}: registering again replaces it. */
    public static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    private static Set<String> installed() {
        Set<String> s = installed;
        if (s != null) return s;
        try {
            Path dir = QTraceUpdater.extensionsDir(QTraceUpdater.class);
            s = Set.copyOf(ModuleUpdates.localVersions(dir).keySet());
        } catch (Exception e) {
            s = Set.of();
        }
        installed = s;
        return s;
    }

    /** What a click on a greyed button says. */
    public static void explain(javafx.stage.Window owner, String tool) {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        a.setTitle("qTrace");
        a.setHeaderText(tool + " is installed");
        a.setContentText(QTraceI18n.t("pending.restart"));
        if (owner != null) a.initOwner(owner);
        a.show();
    }
}
