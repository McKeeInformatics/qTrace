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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolContentTest {

    private static final ToolContent.Item PLAYER = new ToolContent.Item("player", "qTrace Player",
        "Replay a recorded analysis.", "1.0.0", "player",
        List.of("Step by step", "In batch", "Pre-flight check"),
        List.of(new ToolContent.Slide("Open a record", "Paste a qtc_… ID.", "/docs/bricks/screenshots/player.png"),
                new ToolContent.Slide("Pick your images", "Tick them in Target images.", null)));
    private static final ToolContent.Item BARE = new ToolContent.Item("cohort", "Cohort map", "", "0.1.0",
        "cohort", List.of("One cell per image"), List.of());

    private static JsonArray slides(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("slides");
    }

    private static List<String> ids(String json) {
        List<String> out = new ArrayList<>();
        slides(json).forEach(s -> out.add(s.getAsJsonObject().get("id").getAsString()));
        return out;
    }

    @Test
    void noToolIsNoContent() {
        assertNull(ToolContent.of(List.of()));
    }

    @Test
    void aToolsWelcomeStartsWithItsButtonAndEssentials_thenItsOwnSlides() {
        String json = ToolContent.of(List.of(PLAYER));
        assertEquals(List.of("tool-player", "tool-player-1", "tool-player-2", "tools-done"), ids(json));
        JsonObject first = slides(json).get(0).getAsJsonObject();
        assertEquals("qTrace Player", first.get("title").getAsString());
        assertEquals("Replay a recorded analysis.", first.get("text").getAsString());
        assertEquals("module", first.get("visual").getAsString());
        JsonObject m = first.getAsJsonObject("module");
        assertEquals("player", m.get("icon").getAsString());
        assertEquals("Player", m.get("label").getAsString());
        assertEquals(3, m.getAsJsonArray("features").size());
        assertTrue(m.get("news").getAsBoolean(), "no download line: the tool is already there");
    }

    @Test
    void aSlideShowsItsCaptureWhenItHasOne() {
        JsonArray s = slides(ToolContent.of(List.of(PLAYER)));
        JsonObject withCapture = s.get(1).getAsJsonObject();
        assertEquals("Open a record", withCapture.get("title").getAsString());
        assertEquals("Paste a qtc_… ID.", withCapture.get("text").getAsString());
        assertEquals("/docs/bricks/screenshots/player.png", withCapture.get("image").getAsString());
        assertEquals("qTrace Player", withCapture.get("eyebrow").getAsString());
        assertFalse(s.get(2).getAsJsonObject().has("image"));
    }

    @Test
    void severalToolsPlayOneAfterTheOther_withOneClosingSlide() {
        assertEquals(List.of("tool-player", "tool-player-1", "tool-player-2", "tool-cohort", "tools-done"),
            ids(ToolContent.of(List.of(PLAYER, BARE))));
    }

    @Test
    void theLastSlideClosesTheWindow() {
        JsonArray s = slides(ToolContent.of(List.of(BARE)));
        JsonObject action = s.get(s.size() - 1).getAsJsonObject().getAsJsonArray("actions").get(0).getAsJsonObject();
        assertEquals("close", action.get("action").getAsString());
    }

    @Test
    void theClosingButtonAlwaysReadsStartUsingQTrace() {
        for (List<ToolContent.Item> tools : List.of(List.of(BARE), List.of(PLAYER, BARE))) {
            JsonArray s = slides(ToolContent.of(tools));
            JsonObject action = s.get(s.size() - 1).getAsJsonObject().getAsJsonArray("actions").get(0).getAsJsonObject();
            assertEquals("Start using qTrace", action.get("label").getAsString());
        }
    }
}
