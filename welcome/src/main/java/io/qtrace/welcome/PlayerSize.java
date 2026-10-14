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

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * How tall the welcome windows are: tall enough for the tallest slide, and the bar under it, to fit
 * without a scroll bar — less on a small screen. Pure arithmetic apart from {@link #apply}: unit-tested.
 */
final class PlayerSize {

    /** The window's outer height when the screen allows it. */
    static final double WANTED = 800;

    private PlayerSize() {}

    /** {@code WANTED}, or 92 % of the screen's usable height when that is smaller. */
    static double heightFor(double screenHeight) {
        return Math.min(WANTED, Math.floor(screenHeight * 0.92));
    }

    static void apply(Stage stage) {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        stage.setHeight(heightFor(screen.getHeight()));
    }
}
