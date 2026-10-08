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
 * The replay player a module brings ({@link Player}). The module registers it when it is
 * installed; Core opens it from the panel, the mini-panel, the menu, the Version window and
 * the modules that play a record. With no module registered there is no player, and nothing
 * that opens one is shown.
 */
public final class Players {

    private static final Map<String, Player> PLAYERS = new LinkedHashMap<>();
    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private Players() {}

    /** @param module the registering module's Qtrace-Module name, e.g. "player" — one player each */
    public static void register(String module, Player player) {
        if (module == null || player == null) return;
        synchronized (PLAYERS) {
            PLAYERS.remove(module);
            PLAYERS.put(module, player);
        }
        for (Runnable l : LISTENERS.values()) l.run();
    }

    /** The first registered player of a module the licence includes; null when there is none. */
    public static Player entitled() {
        synchronized (PLAYERS) {
            for (Map.Entry<String, Player> e : PLAYERS.entrySet())
                if (ModuleEntitlements.isEntitled(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** @return false when there is no player to open */
    public static boolean open() {
        Player player = entitled();
        if (player == null) return false;
        player.open();
        return true;
    }

    /** @return false when there is no player to open */
    public static boolean open(String source) {
        Player player = entitled();
        if (player == null) return false;
        player.open(source);
        return true;
    }

    /**
     * Called (on the registering thread) each time a player is registered — a module can load
     * after the panel was drawn. One listener per {@code owner}: registering again replaces it.
     */
    public static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    static void clear() {
        synchronized (PLAYERS) { PLAYERS.clear(); }
        LISTENERS.clear();
    }
}
