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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Blocks that modules add to the Validate &amp; Stamp dialog ({@link StampSection}). A module
 * registers a factory once, when it is installed; the dialog builds a fresh block each time it
 * opens. With no module registered the dialog is unchanged — nor with a module the licence no
 * longer includes ({@link ModuleEntitlements}).
 */
public final class StampSections {

    private record Entry(String module, Supplier<StampSection> factory) {}

    private static final List<Entry> FACTORIES = new CopyOnWriteArrayList<>();

    private StampSections() {}

    /** @param module the registering module's Qtrace-Module name, e.g. "security" */
    public static void register(String module, Supplier<StampSection> factory) {
        if (module != null && factory != null) FACTORIES.add(new Entry(module, factory));
    }

    /**
     * One block per registered factory, in registration order. Skipped: a module the licence no
     * longer includes, and a factory that fails.
     */
    static List<StampSection> create() {
        List<StampSection> out = new ArrayList<>();
        for (Entry e : FACTORIES) {
            if (!ModuleEntitlements.isEntitled(e.module())) continue;
            try {
                StampSection s = e.factory().get();
                if (s != null) out.add(s);
            } catch (RuntimeException ex) {
                // A module's block must never keep the validator from stamping.
            }
        }
        return out;
    }

    static boolean allReady(List<StampSection> sections) {
        for (StampSection s : sections) if (!s.ready().get()) return false;
        return true;
    }

    static void clear() {
        FACTORIES.clear();
    }
}
