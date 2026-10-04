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
 * Sources that modules add to the replay player's Open menu ({@link PlayerSource}). A module
 * registers one when it is installed; the player lists those of the modules the licence
 * includes ({@link ModuleEntitlements}) each time it builds its menu. With no module registered
 * the player is unchanged.
 */
public final class PlayerSources {

    private static final Map<String, PlayerSource> SOURCES = new LinkedHashMap<>();

    private PlayerSources() {}

    /** @param module the registering module's Qtrace-Module name, e.g. "training" — one source each */
    public static synchronized void register(String module, PlayerSource source) {
        if (module == null || source == null) return;
        SOURCES.remove(module);
        SOURCES.put(module, source);
    }

    /** In registration order, without the modules the licence does not include. */
    public static synchronized List<PlayerSource> entitled() {
        List<PlayerSource> out = new ArrayList<>();
        SOURCES.forEach((module, source) -> {
            if (ModuleEntitlements.isEntitled(module)) out.add(source);
        });
        return out;
    }

    static synchronized void clear() {
        SOURCES.clear();
    }
}
