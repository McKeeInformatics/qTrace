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
 * opens. With no module registered the dialog is unchanged.
 */
public final class StampSections {

    private static final List<Supplier<StampSection>> FACTORIES = new CopyOnWriteArrayList<>();

    private StampSections() {}

    public static void register(Supplier<StampSection> factory) {
        if (factory != null) FACTORIES.add(factory);
    }

    /** One block per registered factory, in registration order; a factory that fails is skipped. */
    static List<StampSection> create() {
        List<StampSection> out = new ArrayList<>();
        for (Supplier<StampSection> f : FACTORIES) {
            try {
                StampSection s = f.get();
                if (s != null) out.add(s);
            } catch (RuntimeException e) {
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
