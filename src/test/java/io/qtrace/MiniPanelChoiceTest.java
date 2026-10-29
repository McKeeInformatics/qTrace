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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MiniPanelChoiceTest {

    @Test
    void aToolTurnedOnGoesAtTheBottomOfTheMiniPanel() {
        assertEquals(List.of("stamp", "player", "logbook"),
            MiniPanelChoice.toggle(List.of("stamp", "player"), "logbook"));
    }

    @Test
    void aToolTurnedOffLeavesTheOthersInTheirOrder() {
        assertEquals(List.of("stamp", "reset"),
            MiniPanelChoice.toggle(List.of("stamp", "player", "reset"), "player"));
    }

    @Test
    void aBlockMovesUpOrDownInTheMiniPanel() {
        List<String> tools = List.of("stamp", "player", "versions", "reset");
        assertEquals(List.of("versions", "stamp", "player", "reset"), MiniPanelChoice.move(tools, 2, 0));
        assertEquals(List.of("player", "versions", "stamp", "reset"), MiniPanelChoice.move(tools, 0, 2));
        assertEquals(tools, MiniPanelChoice.move(tools, 1, 1));
        assertEquals(tools, MiniPanelChoice.move(tools, -1, 9)); // out of range: nothing moves
    }

    @Test
    void theMiniPanelShowsOnlyTheChosenToolsThePanelHas() {
        // Upload chosen, but the licence went: it is skipped, and comes back with the licence.
        assertEquals(List.of("player", "stamp"),
            MiniPanelChoice.shown(List.of("player", "upload", "stamp"), Set.of("stamp", "player", "reset")));
    }

    @Test
    void aChoiceIsReadWithoutDuplicatesOrBlanks() {
        assertEquals(List.of("stamp", "player"),
            MiniPanelChoice.clean(java.util.Arrays.asList("stamp", null, " ", "player", "stamp")));
        assertNull(MiniPanelChoice.clean(null));
    }
}
