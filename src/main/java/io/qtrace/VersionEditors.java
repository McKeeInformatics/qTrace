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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Editing modes that modules add to the Version window ({@link VersionEditor}). A module
 * registers one when it is installed; the window offers those of the modules the licence
 * includes ({@link ModuleEntitlements}). With no module registered the window is unchanged.
 * Twin of {@link PlayerSources}.
 */
public final class VersionEditors {

    private static final Map<String, VersionEditor> EDITORS = new LinkedHashMap<>();

    private VersionEditors() {}

    /** @param module the registering module's Qtrace-Module name, e.g. "workfloweditor" — one editor each */
    public static synchronized void register(String module, VersionEditor editor) {
        if (module == null || editor == null) return;
        EDITORS.remove(module);
        EDITORS.put(module, editor);
    }

    /** In registration order, without the modules the licence does not include. */
    public static synchronized List<VersionEditor> entitled() {
        List<VersionEditor> out = new ArrayList<>();
        EDITORS.forEach((module, editor) -> {
            if (ModuleEntitlements.isEntitled(module)) out.add(editor);
        });
        return out;
    }

    static synchronized void clear() {
        EDITORS.clear();
    }
}
