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
import java.util.function.Consumer;

/**
 * Tells modules that an image was just stamped — after its .qtrace was written — so a view of
 * the project (e.g. the cohort map) can update that one image instead of reading everything
 * again. A module registers one listener when it is installed; only the modules the licence
 * includes are told ({@link ModuleEntitlements}). Called on the FX thread.
 */
public final class StampEvents {

    private static final Map<String, Consumer<String>> LISTENERS = new LinkedHashMap<>();

    private StampEvents() {}

    /** @param module the registering module's Qtrace-Module name, e.g. "cohort" — one listener each */
    public static synchronized void register(String module, Consumer<String> onStamped) {
        if (module != null && onStamped != null) LISTENERS.put(module, onStamped);
    }

    /** @param imageName the stamped image, as its server names it (what its .qtrace records) */
    static synchronized void stamped(String imageName) {
        if (imageName == null) return;
        LISTENERS.forEach((module, listener) -> {
            if (!ModuleEntitlements.isEntitled(module)) return;
            try {
                listener.accept(imageName);
            } catch (RuntimeException | LinkageError e) {
                // A module's view must never get in the way of a stamp.
            }
        });
    }

    static synchronized void clear() {
        LISTENERS.clear();
    }
}
