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

package io.qtrace.player.ext;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class ExtensionHandlerRegistry {

    private static final ExtensionHandlerRegistry INSTANCE = new ExtensionHandlerRegistry();

    private final CopyOnWriteArrayList<QTraceExtensionHandler> handlers = new CopyOnWriteArrayList<>();

    private ExtensionHandlerRegistry() {}

    public static ExtensionHandlerRegistry get() { return INSTANCE; }

    /** Append a handler (lowest priority). */
    public void register(QTraceExtensionHandler h) {
        handlers.add(h);
    }

    /** Prepend a handler (highest priority — wins on simple-name conflicts). */
    public void registerPriority(QTraceExtensionHandler h) {
        handlers.add(0, h);
    }

    public Optional<QTraceExtensionHandler> findByName(String keyword) {
        String kw = keyword.toLowerCase();
        return handlers.stream()
            .filter(h -> h.getExtensionName().equalsIgnoreCase(kw))
            .findFirst();
    }

    /**
     * Returns handlers whose preamble should be injected given the set of
     * extension names recorded in the .qtrace file.
     */
    public List<QTraceExtensionHandler> activePreambleHandlers(Collection<String> usedExtensions) {
        return handlers.stream()
            .filter(h -> h.needsPreamble(usedExtensions))
            .collect(Collectors.toList());
    }

    public List<QTraceExtensionHandler> all() {
        return List.copyOf(handlers);
    }

    /** For testing only — clears all registrations. */
    void clear() { handlers.clear(); }
}
