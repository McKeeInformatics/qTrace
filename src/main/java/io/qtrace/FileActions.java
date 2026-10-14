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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What modules offer to do with a kind of file (a {@code .qtflow}, …): a module registers an
 * action when it is installed; a window that shows such a file offers those of the modules the
 * licence includes ({@link ModuleEntitlements}), as a button. With no module registered the
 * window offers nothing more. Twin of {@link PlayerSources}.
 */
public final class FileActions {

    /** One action: the button's text, and what it does with the file. */
    public record Action(String label, Consumer<File> onFile) {
        public void run(File file) { onFile.accept(file); }
    }

    private record Entry(String extension, Action action) {}

    private static final Map<String, Entry> ACTIONS = new LinkedHashMap<>();

    private FileActions() {}

    /**
     * @param module    the registering module's Qtrace-Module name — one action each
     * @param extension the kind of file, e.g. "qtflow" (with or without its dot)
     */
    public static synchronized void register(String module, String extension, String label, Consumer<File> onFile) {
        if (module == null || extension == null || label == null || onFile == null) return;
        ACTIONS.remove(module);
        ACTIONS.put(module, new Entry(normalize(extension), new Action(label, onFile)));
    }

    /** For this kind of file, in registration order, without the modules the licence does not include. */
    public static synchronized List<Action> entitled(String extension) {
        List<Action> out = new ArrayList<>();
        if (extension == null) return out;
        String ext = normalize(extension);
        ACTIONS.forEach((module, entry) -> {
            if (entry.extension().equals(ext) && ModuleEntitlements.isEntitled(module)) out.add(entry.action());
        });
        return out;
    }

    private static String normalize(String extension) {
        String e = extension.strip().toLowerCase(Locale.ROOT);
        return e.startsWith(".") ? e.substring(1) : e;
    }

    static synchronized void clear() {
        ACTIONS.clear();
    }
}
