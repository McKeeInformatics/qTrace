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
import io.qtrace.Provisioner.OpenModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrunkContentTest {

    private static final String TRUNK = """
        {"schemaVersion":2,"title":"Getting started with qTrace","slides":[
          {"id":"what","title":"Every analysis, traceable","text":"…","actions":[]},
          {"id":"network","title":"Checking your connection","text":"…","visual":"network","actions":[]},
          {"id":"account","title":"Do you have an invitation code?","text":"…","actions":[{"label":"Not now","action":"continue"}]},
          {"id":"ready","hidden":true,"title":"qTrace is on board","text":"Work as usual.",
           "actions":[{"label":"Start using qTrace","action":"close","primary":true}]}]}""";

    private static final OpenModule PLAYER = new OpenModule("player", "qTrace Player", true);

    private static JsonArray slides(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("slides");
    }

    private static List<String> ids(String json) {
        List<String> out = new ArrayList<>();
        slides(json).forEach(s -> out.add(s.getAsJsonObject().get("id").getAsString()));
        return out;
    }

    private static JsonObject slide(String json, String id) {
        for (var s : slides(json)) if (s.getAsJsonObject().get("id").getAsString().equals(id)) return s.getAsJsonObject();
        return null;
    }

    @Test
    void withoutAnOpenModuleTheTrunkIsUnchanged() {
        assertEquals(JsonParser.parseString(TRUNK), JsonParser.parseString(TrunkContent.withOpenModules(TRUNK, List.of())));
    }

    @Test
    void theModulesAreNotPresentedHere_theirWelcomePlaysWhenTheToolShowsInThePanel() {
        var cohort = new OpenModule("cohort", "Cohort map", true);
        assertEquals(List.of("what", "network", "account", "ready"), ids(TrunkContent.withOpenModules(TRUNK, List.of(PLAYER, cohort))));
    }

    @Test
    void whenAModuleIsBeingInstalledTheLastSlideOffersToRestart() {
        JsonObject ready = slide(TrunkContent.withOpenModules(TRUNK, List.of(PLAYER)), "ready");
        assertTrue(ready.get("text").getAsString().contains("qTrace Player"));
        JsonArray actions = ready.getAsJsonArray("actions");
        assertEquals("quit", actions.get(0).getAsJsonObject().get("action").getAsString());
        assertTrue(actions.get(0).getAsJsonObject().get("primary").getAsBoolean());
        assertEquals("close", actions.get(1).getAsJsonObject().get("action").getAsString());
        assertFalse(actions.get(1).getAsJsonObject().has("primary"));
    }

    @Test
    void whenEveryOpenModuleIsAlreadyInstalledTheTrunkIsUnchanged() {
        var here = new OpenModule("player", "qTrace Player", false);
        assertEquals(JsonParser.parseString(TRUNK), JsonParser.parseString(TrunkContent.withOpenModules(TRUNK, List.of(here))));
    }
}
