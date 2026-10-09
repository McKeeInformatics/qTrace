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
 * The Dashboard, as the module that carries it offers it to Core ({@link Dashboards}). Core
 * creates a window when the user asks for it, from the panel, the mini-panel or the menus.
 */
@FunctionalInterface
public interface Dashboard {

    /** A new Dashboard window on the open project's records, not shown yet. Called on the FX thread. */
    Window create();

    /** One Dashboard window. Every method is called on the FX thread. */
    interface Window {
        void show();
        boolean isShowing();
        boolean isIconified();
        void minimize();
    }
}
