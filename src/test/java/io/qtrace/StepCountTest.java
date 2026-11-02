/*
 * qTrace - QuPath workflow provenance extension
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The step counter of the panel and the "already stamped" reminder count what is in force:
 * a step QuPath's Undo took back is not an instruction of the record.
 */
class StepCountTest {

    @Test
    void anUndoneOrAbandonedStepIsNotCounted() {
        JsonObject kept = UndoneStepsTest.step("Cell detection", 1);
        JsonObject undone = UndoneStepsTest.undone(UndoneStepsTest.step("Expand annotations", 2), "undone");
        JsonObject abandoned = UndoneStepsTest.undone(UndoneStepsTest.step("Manual annotation: Polygon", 3), "abandoned");
        assertEquals(1, QTraceExporter.countStepsNotUndone(List.of(kept, undone, abandoned)));
    }

    @Test
    void aRetiredStepStaysCountedAsBefore() {
        JsonObject retired = UndoneStepsTest.step("Manual annotation: Rectangle", 1);
        retired.addProperty("deleted", true);
        assertEquals(1, QTraceExporter.countStepsNotUndone(List.of(retired)));
    }
}
