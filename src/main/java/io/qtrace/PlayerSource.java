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

import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * A place a qTrace module offers the replay player to open a record from, next to "Local
 * .qtrace file…" and "qtrace.ca ID or share link…" (e.g. the training module's catalogue).
 * Registered through {@link PlayerSources}; Core and the player know nothing about what it lists.
 */
public interface PlayerSource {

    /** The entry in the player's Open menu, e.g. "Training…". */
    String label();

    /**
     * Lets the user pick a record, then hands it to {@code open} — a {@code qtc_…} ID, a share
     * link or a local path, exactly what the player's own entries accept. Called on the FX
     * thread; {@code open} must be called on it too, and not at all when the user gives up.
     */
    void choose(Window owner, Consumer<String> open);
}
