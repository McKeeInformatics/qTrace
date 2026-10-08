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

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;

/**
 * The Version window, as the module that carries it offers it to Core ({@link VersionGraphs}).
 * Core creates one when the user asks for it and tells it what happens to the open image.
 */
public interface VersionGraph {

    /** A new Version window, not shown yet. Called on the FX thread. */
    Window create(Host host);

    /** One Version window. Every method is called on the FX thread. */
    interface Window {
        /**
         * Opens the graph for the given .qtrace or .qtflow. Without one, shows the open image's
         * capture in progress when the host has one, else prompts a chooser.
         */
        void show(File record);

        /**
         * Another image was opened in QuPath: the window switches to its history ({@code current}:
         * its .qtrace, null when it has none yet — then only its capture in progress, if any).
         */
        void showImage(File current);

        /**
         * The open image's capture or its .qtrace changed (a step captured, a session committed).
         * {@code current} is the open image's .qtrace, adopted when the window was opened before
         * that file existed.
         */
        void refresh(File current);

        /** Enters the editing mode a module adds ({@link VersionEditor}), if not already in it. */
        void startEditing();

        boolean isEditing();
        boolean isShowing();
        boolean isIconified();
        void minimize();
        void front();
    }

    /**
     * The open image, as far as the Version window is concerned. Without a host (screenshot
     * harness) the window only reads the file: no "in progress" session, no −/+ on the steps.
     */
    interface Host {
        /**
         * The capture in progress of the image {@code qtrace} belongs to ({@code null}: the open
         * image has no .qtrace yet), as a session flagged {@code "live"} — or null when that
         * image is not the one open, or nothing was captured.
         */
        JsonObject liveSession(File qtrace);

        /** The .qtrace of the image open in QuPath, or null when it has none yet (or no image is open). */
        default File currentRecord() { return null; }

        /** Opens the replay player on a source (a qtw_… ID, …); false when there is no player to open. */
        default boolean playInPlayer(String source) { return false; }

        /** Takes an instruction out of the replay or puts it back, wherever it is still unstamped. */
        void setReplaySkip(File qtrace, String fragment, boolean skip) throws IOException;
    }
}
