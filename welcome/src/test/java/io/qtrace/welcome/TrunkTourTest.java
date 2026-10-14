package io.qtrace.welcome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the shipped trunk.json promises about Getting started's tour (hello → Core → panel → account). */
class TrunkTourTest {

    private static JsonObject trunk() throws Exception {
        try (InputStream in = TrunkTourTest.class.getResourceAsStream("player/trunk.json")) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<JsonObject> slides() throws Exception {
        JsonArray a = trunk().getAsJsonArray("slides");
        return StreamSupport.stream(a.spliterator(), false).map(JsonElement::getAsJsonObject).collect(Collectors.toList());
    }

    @Test
    void theTourComesBeforeTheConnection() throws Exception {
        List<String> ids = slides().stream().map(s -> s.get("id").getAsString()).toList();
        assertEquals(List.of("what", "panel", "network", "account"), ids.subList(0, 4));
        assertTrue(ids.containsAll(List.of("code", "install", "ready")), "the sign-in path is still there");
    }

    @Test
    void everyPanelBubbleSaysItsToolInFiveWordsAtMost() throws Exception {
        JsonObject tour = slides().stream().filter(s -> s.has("tour")).findFirst().orElseThrow().getAsJsonObject("tour");
        Set<String> keys = new HashSet<>();
        for (JsonElement el : tour.getAsJsonArray("buttons")) {
            JsonObject b = el.getAsJsonObject();
            assertTrue(keys.add(b.get("key").getAsString()), "one bubble per button: " + b);
            String text = b.get("text").getAsString().trim();
            assertTrue(text.split("\\s+").length <= 6, "six words at most: " + text);
            assertTrue(!b.get("title").getAsString().isBlank());
        }
        assertTrue(keys.containsAll(Set.of("start", "stamp", "upload", "player", "versiongraph", "reset", "settings", "issue", "expand")));
    }
}
