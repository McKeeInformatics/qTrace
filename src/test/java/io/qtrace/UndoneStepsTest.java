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

import static org.junit.jupiter.api.Assertions.*;

/** What comes out once QuPath undid an action: only what is in force replays, the rest is kept apart. */
class UndoneStepsTest {

    static JsonObject step(String command, long action) {
        JsonObject st = new JsonObject();
        st.addProperty("command", command);
        st.addProperty("script_fragment", command + "()");
        st.addProperty("is_scriptable", true);
        st.addProperty("action", action);
        return st;
    }

    static JsonObject undone(JsonObject st, String state) {
        JsonObject u = new JsonObject();
        u.addProperty("state", state);
        u.addProperty("at", "2026-11-02T10:00:03Z");
        st.add("undo", u);
        return st;
    }

    final JsonObject detection = step("Cell detection", 7);
    final JsonObject expand = undone(step("Expand annotations", 9), "undone");
    final JsonObject dropped = undone(step("Manual annotation: Polygon", 10), "abandoned");
    final List<JsonObject> captured = List.of(detection, expand, dropped);

    @Test
    void theSessionStepsHoldOnlyWhatIsInForce() {
        assertEquals(List.of(detection), QTraceExporter.selectSessionSteps(captured));
    }

    @Test
    void whatWasDoneThenUndoneIsKeptApart() {
        var apart = QTraceExporter.undoneSteps(captured);
        assertEquals(2, apart.size());
        assertEquals("Expand annotations", apart.get(0).getAsJsonObject().get("command").getAsString());
        assertEquals("abandoned", apart.get(1).getAsJsonObject().getAsJsonObject("undo").get("state").getAsString());
    }

    @Test
    void anUndoneStepIsNotReplayed() {
        assertTrue(ReplaySkip.isReplayable(detection));
        assertFalse(ReplaySkip.isReplayable(expand));
        assertFalse(QTraceExporter.inForce(expand));
        assertTrue(QTraceExporter.inForce(detection));
    }

    @Test
    void aStepOutOfAReplayIsNotKeptApartEither() {
        JsonObject firstOpen = undone(step("Set image type", 3), "undone");
        firstOpen.addProperty("replay_excluded", true);
        assertTrue(QTraceExporter.undoneSteps(List.of(firstOpen)).isEmpty());
    }
}
