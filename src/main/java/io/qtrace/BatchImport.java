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
 * Import — the batch that writes a stamped .qtrace for every image of the project — as the
 * module that carries it offers it to Core ({@link BatchImports}). Core runs it from the
 * Import button of the panel and the mini-panel.
 */
@FunctionalInterface
public interface BatchImport {

    /**
     * The Import button was clicked. Called on the FX thread. A toggle: it closes its pre-flight
     * dialog when that one is up, and brings a running batch's window back to the front.
     */
    void start(QTraceController controller);
}
