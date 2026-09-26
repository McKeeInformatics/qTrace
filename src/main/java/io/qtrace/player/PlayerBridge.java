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

package io.qtrace.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What the HTML onboarding player may ask QuPath to do (docs/architecture/loader.md § 17): a
 * whitelist of named actions, each with one string argument. Anything else is refused. No
 * JavaFX: unit-tested; {@link PlayerWindow} exposes it to the page as {@code window.qtrace}.
 */
public final class PlayerBridge {

    private static final Logger log = LoggerFactory.getLogger(PlayerBridge.class);

    private final Map<String, Consumer<String>> actions = new LinkedHashMap<>();

    /** Registers {@code action}; the handler runs on the JavaFX thread (WebView callbacks do). */
    public PlayerBridge on(String action, Consumer<String> handler) {
        actions.put(action, handler);
        return this;
    }

    public Set<String> actions() {
        return Collections.unmodifiableSet(actions.keySet());
    }

    /** Runs a registered action; returns false (and does nothing) for any other name. */
    public boolean dispatch(String action, String arg) {
        Consumer<String> h = action == null ? null : actions.get(action);
        if (h == null) {
            log.warn("[qtrace-player] refused action {}", action);
            return false;
        }
        try {
            h.accept(arg == null ? "" : arg);
        } catch (RuntimeException e) {
            log.warn("[qtrace-player] action {} failed", action, e);
        }
        return true;
    }

    /**
     * True when the page at {@code location} is the player itself ({@code playerUrl}, possibly
     * with a #fragment or ?query): only then does it get the bridge.
     */
    public static boolean trustedLocation(String playerUrl, String location) {
        if (playerUrl == null || location == null || !location.startsWith(playerUrl)) return false;
        if (location.length() == playerUrl.length()) return true;
        char next = location.charAt(playerUrl.length());
        return next == '#' || next == '?';
    }

    /** False on a QuPath whose JavaFX has no WebView: callers then use their plain dialogs. */
    public static boolean webViewAvailable() {
        try {
            Class.forName("javafx.scene.web.WebView", false, PlayerBridge.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
