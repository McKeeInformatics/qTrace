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

package io.qtrace.welcome;

import io.qtrace.InstallWindow;
import io.qtrace.QTraceUpdater;
import io.qtrace.tools.PlayerBridge;
import io.qtrace.tools.PlayerWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The window of an accepted update (Core's {@link InstallWindow}): Getting started's install page
 * on its own (player/update.json) — each module with its progress bar, then "Quit & Relaunch
 * QuPath now" or Later. The provisioning module installs and reports here. Its own class: under
 * a Core published before {@link InstallWindow}, only this class fails to load.
 */
final class UpdateInstallWindow {

    private static final Logger log = LoggerFactory.getLogger(UpdateInstallWindow.class);

    private UpdateInstallWindow() {}

    static void register(QuPathGUI qupath) {
        InstallWindow.register(() -> open(qupath));
    }

    /** Null on a QuPath without WebView, or without the content: the plain message then. */
    private static InstallWindow.Progress open(QuPathGUI qupath) {
        if (!PlayerBridge.webViewAvailable()) return null;
        String content;
        try (InputStream in = UpdateInstallWindow.class.getResourceAsStream("player/update.json")) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[qtrace-welcome] update window content missing, plain message instead", e);
            return null;
        }
        String playerUrl = UpdateInstallWindow.class.getResource("player/player.html").toExternalForm();
        PlayerBridge bridge = new PlayerBridge();
        PlayerWindow w = new PlayerWindow(qupath.getStage(), "Updating qTrace", playerUrl, content, bridge);
        PlayerSize.apply(w.stage());
        bridge.on("close", a -> w.close())
            .on("quit", a -> {
                w.close();
                // "Quit & Relaunch QuPath now": QuPath comes back by itself once it has exited.
                Relauncher.schedule();
                QTraceUpdater.whenNoModalOpen("quit request", qupath::sendQuitRequest);
            });
        w.show();
        w.emit("install", Map.of("state", "running"));

        // Each module of the update, in the order it was announced, with how far its download is.
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        return new InstallWindow.Progress() {
            @Override public void step(String module, String title, String version, double fraction) {
                synchronized (rows) {
                    rows.put(module, Map.of("name", title, "version", version, "pct", Math.max(0, Math.round(fraction * 100)),
                        "done", fraction >= 1, "failed", fraction < 0));
                    w.emit("install", Map.of("state", "running", "modules", List.copyOf(rows.values())));
                }
            }

            @Override public void done(int modules) {
                synchronized (rows) {
                    w.emit("install", Map.of("state", "done", "count", modules, "modules", List.copyOf(rows.values())));
                }
            }
        };
    }
}
