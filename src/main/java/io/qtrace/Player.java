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

/**
 * The replay player, as the module that carries it offers it to Core ({@link Players}). Core
 * only knows how to ask for it: empty, or on a record.
 */
public interface Player {

    /** Opens the player with nothing loaded. May be called from any thread. */
    void open();

    /**
     * Opens the player on a source — a {@code qtc_…} or {@code qtw_…} ID, a share link or a
     * local path — as if the user had given it to the player. May be called from any thread.
     */
    void open(String source);
}
