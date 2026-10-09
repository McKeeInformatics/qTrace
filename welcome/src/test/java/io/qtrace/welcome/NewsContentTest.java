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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NewsContentTest {

    private static final NewsContent.Item PLAYER = new NewsContent.Item("player", "qTrace Player",
        "1.1.0", "player", List.of("Sessions as circles", "Scripts shown"));
    private static final NewsContent.Item SILENT = new NewsContent.Item("cohort", "Cohort map",
        "0.2.0", "cohort", List.of());

    private static JsonArray slides(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("slides");
    }

    @Test
    void nothingToSayIsNoContent() {
        assertNull(NewsContent.of(List.of()));
        assertNull(NewsContent.of(List.of(SILENT)), "an update nobody wrote about is not presented");
    }

    @Test
    void eachUpdatedModuleThatHasNotesGetsOneSlide() {
        JsonArray slides = slides(NewsContent.of(List.of(PLAYER, SILENT)));
        assertEquals(2, slides.size(), "the player's slide, then the closing one");
        JsonObject s = slides.get(0).getAsJsonObject();
        assertEquals("news-player", s.get("id").getAsString());
        assertEquals("qTrace Player", s.get("title").getAsString());
        assertTrue(s.get("eyebrow").getAsString().contains("1.1.0"));
        assertEquals("module", s.get("visual").getAsString());
        JsonObject m = s.getAsJsonObject("module");
        assertEquals("player", m.get("icon").getAsString());
        assertEquals("Player", m.get("label").getAsString());
        assertTrue(m.get("news").getAsBoolean());
        assertEquals("Scripts shown", m.getAsJsonArray("features").get(1).getAsString());
    }

    @Test
    void theLastSlideClosesTheWindow() {
        JsonArray slides = slides(NewsContent.of(List.of(PLAYER)));
        JsonObject last = slides.get(slides.size() - 1).getAsJsonObject();
        JsonObject action = last.getAsJsonArray("actions").get(0).getAsJsonObject();
        assertEquals("close", action.get("action").getAsString());
        assertTrue(action.get("primary").getAsBoolean());
    }
}
