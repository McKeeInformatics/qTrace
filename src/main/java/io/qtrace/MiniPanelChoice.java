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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The buttons this user chose for the mini-panel, in the order they chose — set from the full
 * panel's mini-panel mode (the blue gear). Tools are named as in {@link PanelProfile}
 * ({@code stamp}, {@code player}…) or by a module's Qtrace-Module name ({@code logbook}).
 * Null: qTrace's own mini-panel. Kept in {@code ~/.qTrace/config.json}, so it is this
 * workstation's and this user's alone. The fixed parts (capture status, Settings, the bug
 * report, ⤢) are never chosen: they always stay.
 */
public final class MiniPanelChoice {

    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private MiniPanelChoice() {}

    /** The chosen tools, in their order; null when the user never chose (qTrace's own mini-panel). */
    public static List<String> tools() {
        return clean(QTraceConfig.get().getMiniPanelTools());
    }

    /** Saves the choice (null: back to qTrace's own mini-panel) and redraws the mini-panel. FX thread. */
    public static void set(List<String> tools) {
        QTraceConfig cfg = QTraceConfig.get();
        cfg.setMiniPanelTools(clean(tools));
        cfg.save();
        for (Runnable l : LISTENERS.values()) l.run();
    }

    /** One listener per {@code owner}: registering again replaces it. */
    static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    // ── Pure logic ───────────────────────────────────────────────────────────

    /** A tool turned on goes at the bottom; turned off, it leaves the others in their order. */
    static List<String> toggle(List<String> tools, String tool) {
        List<String> out = new ArrayList<>(tools);
        if (!out.remove(tool)) out.add(tool);
        return out;
    }

    /** The block at {@code from} moved to {@code to}; nothing moves when either is out of range. */
    static List<String> move(List<String> tools, int from, int to) {
        List<String> out = new ArrayList<>(tools);
        if (from < 0 || from >= out.size() || to < 0 || to >= out.size()) return out;
        out.add(to, out.remove(from));
        return out;
    }

    /** The chosen tools the panel has right now, in the chosen order. */
    static List<String> shown(List<String> chosen, Collection<String> available) {
        List<String> out = new ArrayList<>();
        for (String t : chosen) if (available.contains(t)) out.add(t);
        return out;
    }

    /** Without blanks or duplicates; null stays null. */
    static List<String> clean(List<String> tools) {
        if (tools == null) return null;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : tools) if (t != null && !t.isBlank()) out.add(t.trim());
        return List.copyOf(out);
    }
}
