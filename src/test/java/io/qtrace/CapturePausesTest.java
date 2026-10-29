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

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CapturePausesTest {

    private static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void aPauseIsDeclaredWithWhatWasNotCaptured() {
        CapturePauses p = new CapturePauses();
        p.pause(T0);
        assertTrue(p.isPaused());
        p.missedStep("Cell detection");
        p.missedStep("Delete selected objects");
        p.missedChange("annotation", 3);
        p.missedChange("display settings", 1);
        p.resume(T0.plusSeconds(90));
        assertFalse(p.isPaused());

        JsonArray json = p.toJson();
        assertEquals(1, json.size());
        var first = json.get(0).getAsJsonObject();
        assertEquals("2026-10-29T10:00:00Z", first.get("paused_at").getAsString());
        assertEquals("2026-10-29T10:01:30Z", first.get("resumed_at").getAsString());
        assertEquals(2, first.get("uncaptured_steps").getAsInt());
        assertEquals("Cell detection", first.getAsJsonArray("uncaptured_commands").get(0).getAsString());
        assertEquals(3, first.getAsJsonObject("uncaptured_changes").get("annotation").getAsInt());
        assertEquals(6, p.missedTotal());
    }

    @Test
    void aPauseStillOpenAtExportSaysSo() {
        CapturePauses p = new CapturePauses();
        p.pause(T0);
        var first = p.toJson().get(0).getAsJsonObject();
        assertTrue(first.get("resumed_at").isJsonNull());
    }

    @Test
    void nothingIsMissedOutsideAPause() {
        CapturePauses p = new CapturePauses();
        p.missedStep("Cell detection");
        p.missedChange("annotation", 1);
        assertEquals(0, p.missedTotal());
        assertTrue(p.isEmpty());
    }

    @Test
    void pausingTwiceOrResumingWithoutAPauseChangesNothing() {
        CapturePauses p = new CapturePauses();
        p.resume(T0);
        p.pause(T0);
        p.pause(T0.plusSeconds(5));
        assertEquals(1, p.toJson().size());
    }

    @Test
    void aDraftKeepsThePausesAcrossACrash() {
        CapturePauses p = new CapturePauses();
        p.pause(T0);
        p.missedStep("Cell detection");
        CapturePauses back = CapturePauses.fromJson(p.toJson());
        assertTrue(back.isPaused());
        assertEquals(1, back.missedTotal());
        back.missedStep("Run script");
        assertEquals(2, back.missedTotal());
    }

    @Test
    void theCommandsListedStayFewTheCountStaysWhole() {
        CapturePauses p = new CapturePauses();
        p.pause(T0);
        for (int i = 0; i < CapturePauses.COMMANDS_LISTED + 10; i++) p.missedStep("step " + i);
        var first = p.toJson().get(0).getAsJsonObject();
        assertEquals(CapturePauses.COMMANDS_LISTED, first.getAsJsonArray("uncaptured_commands").size());
        assertEquals(CapturePauses.COMMANDS_LISTED + 10, first.get("uncaptured_steps").getAsInt());
    }
}
