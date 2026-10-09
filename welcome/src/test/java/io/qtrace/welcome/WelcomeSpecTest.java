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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fallback welcome (no WebView) reads the same v2 content as the HTML player, and must
 * never break on content newer than itself (loader.md § 17).
 */
class WelcomeSpecTest {

    private static final List<String> KNOWN = List.of("unlock-key", "open-url", "open-player", "issue-report");

    @Test
    void turnsSlidesIntoStepsWithTheirFirstAction() {
        WelcomeSpec w = WelcomeSpec.parse("""
            {"schemaVersion":2,"branch":"standard","title":"Welcome to qTrace","who":"Kelly Hunter",
             "slides":[
               {"id":"hello","title":"Welcome, Kelly","text":"Your certified identity is active.","actions":[]},
               {"id":"record","title":"Record your first analysis","text":"…",
                "actions":[{"label":"Open the guide","action":"open-url","url":"https://x/docs"}]},
               {"id":"replay","title":"Replay a recorded workflow","text":"…",
                "actions":[{"label":"Open the Player","action":"open-player","primary":true}]}]}
            """, KNOWN);
        assertEquals("Welcome, Kelly", w.title());
        assertEquals("Your certified identity is active.", w.subtitle());
        assertNull(w.orgName());
        assertEquals(List.of("record", "replay"), w.steps().stream().map(WelcomeSpec.Step::id).toList());
        assertEquals("Record your first analysis", w.steps().get(0).label());
        assertEquals("https://x/docs", w.steps().get(0).url());
    }

    @Test
    void readsTheOrganization() {
        WelcomeSpec w = WelcomeSpec.parse("""
            {"schemaVersion":2,"branch":"org","title":"Propath pilot","org":{"name":"Propath UK","logo":"https://x/logo.png"},
             "slides":[{"id":"hello","title":"Welcome to the Propath pilot, Connor","text":"Non-clinical only.","actions":[]}]}
            """, KNOWN);
        assertEquals("Propath UK", w.orgName());
        assertEquals("https://x/logo.png", w.logoUrl());
        assertEquals("Welcome to the Propath pilot, Connor", w.title());
    }

    @Test
    void ignoresUnknownFields() {
        WelcomeSpec w = WelcomeSpec.parse("""
            {"schemaVersion":2,"hologram":true,"slides":[
              {"id":"a","title":"T","text":"x","visual":"network","colour":"red",
               "actions":[{"label":"Go","action":"open-player","mandatory":true}]}]}
            """, KNOWN);
        assertEquals(1, w.steps().size());
    }

    @Test
    void anUnknownActionBecomesALinkOrDisappears() {
        WelcomeSpec w = WelcomeSpec.parse("""
            {"schemaVersion":2,"slides":[
              {"id":"a","title":"A","text":"x","actions":[{"label":"Run","action":"run-script","url":"https://x/s"}]},
              {"id":"b","title":"B","text":"x","actions":[{"label":"Run","action":"run-script"}]}]}
            """, KNOWN);
        assertEquals(1, w.steps().size());
        assertEquals("open-url", w.steps().get(0).action());
    }

    @Test
    void aFutureSchemaShowsTheTitleOnly() {
        WelcomeSpec w = WelcomeSpec.parse("""
            {"schemaVersion":3,"title":"T","slides":[{"id":"a","title":"A","text":"x",
              "actions":[{"label":"Go","action":"open-player"}]}]}
            """, KNOWN);
        assertEquals("T", w.title());
        assertTrue(w.steps().isEmpty());
    }

    @Test
    void theClosingSlideIsNotAStep() {
        WelcomeSpec w = WelcomeSpec.parse("""
            {"schemaVersion":2,"slides":[{"id":"hello","title":"Hi","text":"x","actions":[]},
              {"id":"done","title":"qTrace is on board","text":"x","visual":"done",
               "actions":[{"label":"Start using qTrace","action":"close","primary":true}]}]}
            """, List.of("close", "open-player"));
        assertTrue(w.steps().isEmpty());
    }

    @Test
    void garbageGivesAPlainWelcome() {
        WelcomeSpec w = WelcomeSpec.parse("not json", KNOWN);
        assertEquals("Welcome to qTrace", w.title());
        assertTrue(w.steps().isEmpty());
    }
}
