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
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The "already stamped" reminder and the stamp's earlier work, once QuPath's Undo is in the record. */
class CaptureChangesTest {

    @Test
    void anUndoThenANewActionAfterAStampIsWorkSinceIt() {
        CaptureChanges changes = new CaptureChanges();
        for (int i = 0; i < 5; i++) changes.bump();      // 5 steps, then the stamp
        long atStamp = changes.now();
        assertFalse(changes.since(atStamp));
        changes.bump();                                   // Ctrl+Z: 4 steps in force
        changes.bump();                                   // a new action: 5 again
        assertTrue(changes.since(atStamp), "the same count of steps, not the same work");
    }

    @Test
    void aStepRedoneThenUndoneDoesNotHideTheOneAnEarlierStampedSessionDid() {
        // Session 1 (stamped) ran the detection; this session ran it again, then undid it.
        JsonObject stamped = UndoneStepsTest.step("Cell detection", 1);
        JsonObject session = new JsonObject();
        session.addProperty("session_id", "s1");
        JsonArray steps = new JsonArray();
        steps.add(stamped);
        session.add("steps", steps);
        JsonObject root = new JsonObject();
        JsonArray sessions = new JsonArray();
        sessions.add(session);
        root.add("sessions", sessions);

        JsonObject again = UndoneStepsTest.undone(UndoneStepsTest.step("Cell detection", 9), "undone");
        List<JsonObject> out = QTraceExporter.withEarlierWork(root, List.of(), List.of(again));
        assertEquals(1, out.size(), "the stamped session's detection is still the image's");
        assertEquals("s1", out.get(0).get("carried_from").getAsString());
    }
}
