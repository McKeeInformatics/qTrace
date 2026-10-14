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

import org.junit.jupiter.api.Test;

import static io.qtrace.player.QTraceMiniPlayerBar.Again.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** ↻ Replay clicked while a player already exists: the idle pill goes away, anything else stays. */
class MiniPlayerToggleTest {

    @Test
    void idlePillIsClosed() {
        assertEquals(CLOSE, QTraceMiniPlayerBar.onReplayAgain(true, false, false));
    }

    @Test
    void runningReplayIsNeverClosed() {
        assertEquals(FOCUS, QTraceMiniPlayerBar.onReplayAgain(true, false, true));
    }

    @Test
    void fullWindowIsBroughtBack() {
        assertEquals(SHOW_WINDOW, QTraceMiniPlayerBar.onReplayAgain(true, true, false));
        assertEquals(SHOW_WINDOW, QTraceMiniPlayerBar.onReplayAgain(false, false, false)); // no pill: window-only player
    }
}
