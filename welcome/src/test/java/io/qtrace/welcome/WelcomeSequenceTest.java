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

class WelcomeSequenceTest {

    private static final String ORG = """
        {"schemaVersion":2,"branch":"org","title":"Welcome to Acme","who":"Ada Lovelace",
         "org":{"name":"Acme"},"slides":[
          {"id":"hello","title":"Hello Ada","actions":[]},
          {"id":"report","title":"Report a bug","actions":[{"label":"Report","action":"issue-report"}]},
          {"id":"done","title":"qTrace is on board","visual":"done",
           "actions":[{"label":"Start using qTrace","action":"close","primary":true}]}]}""";

    private static final String TOOLS = ToolContent.of(List.of(
        new ToolContent.Item("player", "qTrace Player", "Replay.", "0.1.0", "player", List.of("a"), List.of())));

    private static final String NEWS = NewsContent.of(List.of(
        new NewsContent.Item("cohort", "qTrace Cohort", "0.2.0", "cohort", List.of("One more colour"))));

    private static List<String> ids(String json) {
        List<String> out = new ArrayList<>();
        JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("slides")
            .forEach(s -> out.add(s.getAsJsonObject().get("id").getAsString()));
        return out;
    }

    private static JsonObject last(String json) {
        JsonArray s = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("slides");
        return s.get(s.size() - 1).getAsJsonObject();
    }

    private static String closeLabel(String json) {
        for (var a : last(json).getAsJsonArray("actions")) {
            JsonObject o = a.getAsJsonObject();
            if ("close".equals(o.get("action").getAsString())) return o.get("label").getAsString();
        }
        return null;
    }

    @Test
    void nothingToPlayIsNoSequence() {
        assertNull(WelcomeSequence.of());
        assertNull(WelcomeSequence.of(null, null, null));
    }

    @Test
    void theOrganizationThenTheToolsThenTheNews_inOneSequenceWithOneClosingSlide() {
        assertEquals(List.of("hello", "report", "tool-player", "news-cohort", "news-done"),
            ids(WelcomeSequence.of(ORG, TOOLS, NEWS)));
    }

    @Test
    void aMissingPartIsSkipped() {
        assertEquals(List.of("hello", "report", "tool-player", "tools-done"), ids(WelcomeSequence.of(ORG, TOOLS, null)));
        assertEquals(List.of("tool-player", "news-cohort", "news-done"), ids(WelcomeSequence.of(null, TOOLS, NEWS)));
        assertEquals(List.of("hello", "report", "done"), ids(WelcomeSequence.of(ORG, null, null)));
    }

    @Test
    void theLastButtonOfTheSequenceAlwaysSaysStartUsingQTrace() {
        for (String json : List.of(
                WelcomeSequence.of(ORG, TOOLS, NEWS), WelcomeSequence.of(ORG, TOOLS, null),
                WelcomeSequence.of(null, TOOLS, null), WelcomeSequence.of(null, null, NEWS),
                WelcomeSequence.of(ORG, null, null)))
            assertEquals("Start using qTrace", closeLabel(json));
    }

    @Test
    void afterSeveralWelcomesTheClosingSlideSpeaksForAllOfThem() {
        JsonObject closing = last(WelcomeSequence.of(ORG, TOOLS, NEWS));
        assertEquals("Ready when you are", closing.get("title").getAsString());
        String text = closing.get("text").getAsString();
        for (String menu : List.of("Welcome…", "Your tools…", "What's new…")) assertTrue(text.contains(menu), text);
    }

    @Test
    void aWelcomePlayedAloneKeepsItsOwnClosingWords() {
        assertEquals("You are up to date", last(WelcomeSequence.of(null, null, NEWS)).get("title").getAsString());
        assertEquals("qTrace is on board", last(WelcomeSequence.of(ORG)).get("title").getAsString());
    }

    @Test
    void theSequenceKeepsWhoItIsForAndItsOrganization() {
        JsonObject root = JsonParser.parseString(WelcomeSequence.of(ORG, TOOLS, NEWS)).getAsJsonObject();
        assertEquals("Ada Lovelace", root.get("who").getAsString());
        assertEquals("Acme", root.getAsJsonObject("org").get("name").getAsString());
        assertEquals("Welcome to Acme", root.get("title").getAsString());
        assertEquals(2, root.get("schemaVersion").getAsInt());
    }

    @Test
    void anActionOfAnEarlierPartIsKept() {
        JsonObject root = JsonParser.parseString(WelcomeSequence.of(ORG, TOOLS, null)).getAsJsonObject();
        JsonObject report = root.getAsJsonArray("slides").get(1).getAsJsonObject();
        assertEquals("issue-report", report.getAsJsonArray("actions").get(0).getAsJsonObject().get("action").getAsString());
    }

    @Test
    void aPartThatCannotBeReadIsSkipped() {
        assertEquals(List.of("tool-player", "tools-done"), ids(WelcomeSequence.of("not json", TOOLS, null)));
    }
}
