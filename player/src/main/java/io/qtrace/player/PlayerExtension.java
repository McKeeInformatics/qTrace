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

import io.qtrace.Player;
import io.qtrace.Players;
import io.qtrace.QTraceController;
import io.qtrace.player.ext.ExtensionHandlerRegistry;
import io.qtrace.player.ext.HandlerRegistrar;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Brings the replay player to Core ({@link Players}): the minimal player docked under the
 * viewers, whose ⤢ opens the full Player window. Core shows what opens a player only once this
 * module has registered.
 */
public class PlayerExtension implements QuPathExtension {

    private static final Logger log = LoggerFactory.getLogger(PlayerExtension.class);
    private static final String TAG = "[qtrace-player] ";

    private static boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) return;
        installed = true;
        try {
            HandlerRegistrar.registerAll(ExtensionHandlerRegistry.get());
            Players.register("player", new Player() {
                @Override public void open() {
                    QTraceController controller = QTraceController.current();
                    if (controller == null) return;
                    Platform.runLater(() -> QTraceMiniPlayerBar.open(qupath, controller.getLogger()));
                }
                @Override public void open(String source) {
                    QTraceController controller = QTraceController.current();
                    if (controller == null) return;
                    Platform.runLater(() -> QTraceMiniPlayerBar.openSource(qupath, controller.getLogger(), source));
                }
            });
            log.info(TAG + "loaded: the replay player is available");
        } catch (LinkageError e) {
            // A Core published before Players existed: nothing to plug into, nothing breaks.
            log.warn(TAG + "this qTrace Core is too old for the player module — update qTrace Core");
        }
    }

    @Override
    public String getName() {
        return "qTrace Player";
    }

    @Override
    public String getDescription() {
        return "Replays a recorded QuPath analysis on your own images, step by step or in batch, "
            + "with a pre-flight check and a new trace of every run.";
    }
}
