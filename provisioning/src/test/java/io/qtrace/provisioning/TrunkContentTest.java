package io.qtrace.provisioning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    private static final TrunkContent.Module PLAYER = new TrunkContent.Module("player", "qTrace Player", true);

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
        var cohort = new TrunkContent.Module("cohort", "Cohort map", true);
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
        var here = new TrunkContent.Module("player", "qTrace Player", false);
        assertEquals(JsonParser.parseString(TRUNK), JsonParser.parseString(TrunkContent.withOpenModules(TRUNK, List.of(here))));
    }
}
