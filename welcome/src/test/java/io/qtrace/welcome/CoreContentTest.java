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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreContentTest {

    private static JsonArray slides() {
        return JsonParser.parseString(CoreContent.of()).getAsJsonObject().getAsJsonArray("slides");
    }

    @Test
    void theBaseWelcomeAsksForBugsAndFeatureRequests_thenCloses() {
        JsonArray s = slides();
        assertEquals(2, s.size());
        JsonObject report = s.get(0).getAsJsonObject();
        assertEquals("core-report", report.get("id").getAsString());
        assertEquals("report", report.get("visual").getAsString());
        JsonObject action = report.getAsJsonArray("actions").get(0).getAsJsonObject();
        assertEquals("issue-report", action.get("action").getAsString());
        JsonObject done = s.get(1).getAsJsonObject();
        assertEquals("done", done.get("id").getAsString());
        assertEquals(WelcomeSequence.START, done.getAsJsonArray("actions").get(0).getAsJsonObject().get("label").getAsString());
    }

    @Test
    void itPromisesNothingAboutCertification() {
        // a basic account reads it too: it must not talk about a certified name
        assertFalse(CoreContent.of().toLowerCase().contains("certified"));
    }

    @Test
    void inASequence_itComesFirstAndItsClosingSlideGivesWayToTheNextPart() {
        String tools = ToolContent.of(java.util.List.of(
            new ToolContent.Item("player", "qTrace Player", "Replay.", "0.1.0", "player", java.util.List.of("a"), java.util.List.of())));
        JsonArray s = JsonParser.parseString(WelcomeSequence.of(CoreContent.of(), tools)).getAsJsonObject().getAsJsonArray("slides");
        assertEquals("core-report", s.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("tool-player", s.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals(2 + 1, s.size(), "report, the tool's card, one closing slide");
    }
}
