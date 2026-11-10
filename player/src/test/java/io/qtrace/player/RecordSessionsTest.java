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

package io.qtrace.player;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The sessions of a record (.qtrace, certificate) as the player draws them, one circle each. */
class RecordSessionsTest {

    private static final String RECORD = """
        { "qtrace_format": "1.0", "sessions": [
          { "session_id": "s1", "exported_at": "2026-07-07T02:47:59Z",
            "validation": { "validator": "A", "timestamp": "2026-07-07T02:47:59Z" },
            "steps": [ { "command": "A", "is_scriptable": true, "script_fragment": "a()" } ] },
          { "session_id": "s2", "exported_at": "2026-07-29T02:25:48Z",
            "validation": { "validator": "A", "timestamp": "2026-07-29T02:25:48Z" },
            "steps": [ { "command": "A", "is_scriptable": true, "script_fragment": "a()" },
                       { "command": "B", "is_scriptable": true, "script_fragment": "b()" } ] },
          { "session_id": "s3", "exported_at": "2026-08-02T10:00:00Z", "validation_state": "unstamped",
            "steps": [] } ] }""";

    @Test
    void eachSessionIsNamedByWhatItIsAndItsDay() {
        assertEquals(List.of("Stamped 2026-07-07", "Certified 2026-07-29", "Unstamped 2026-08-02"),
            RecordSessions.titles(RECORD, 1));
    }

    @Test
    void withoutACertificateNoSessionIsCalledCertified() {
        assertEquals(List.of("Stamped 2026-07-07", "Stamped 2026-07-29", "Unstamped 2026-08-02"),
            RecordSessions.titles(RECORD, -1));
    }

    @Test
    void aSessionWithoutADateKeepsItsKindOnly() {
        assertEquals(List.of("Unstamped"), RecordSessions.titles("{ \"sessions\": [ { \"steps\": [] } ] }", -1));
    }

    @Test
    void aSessionCountsTheInstructionsItRecordedItself() {
        // s2 carries a() again from s1: it is s1's, as on the portal's "Scripts executed"
        assertEquals(List.of(1, 1, 0), RecordSessions.instructionCounts(RECORD));
    }

    @Test
    void nothingReadableGivesNoSession() {
        assertEquals(List.of(), RecordSessions.titles("not json", -1));
        assertEquals(List.of(), RecordSessions.titles(null, -1));
        assertEquals(List.of(), RecordSessions.instructionCounts("{}"));
    }

    private static final String GRAPH = """
        { "sessions": [
          { "session_id": "s1", "user": "Connor McCracken", "exported_at": "2026-10-23T14:02:00Z",
            "validation": { "validator": "Connor McCracken", "confidence": "High",
                            "notes": "tissue outline and stain check", "validatorKeyPub": "MCow" },
            "steps": [] },
          { "session_id": "s2", "user": "romain", "exported_at": "2026-10-24T09:00:00Z",
            "validation": { "validator": "Romain", "confidence": "Medium", "classifier_fidelity": "DEGRADED" },
            "steps": [] },
          { "session_id": "s3", "exported_at": "2026-10-25T09:00:00Z", "validation_state": "unstamped",
            "steps": [] } ] }""";

    @Test
    void aSessionIsDrawnLikeTheVersionGraphOfItsRecordPage() {
        List<RecordSessions.Node> nodes = RecordSessions.nodes(GRAPH, 0);
        assertEquals(3, nodes.size());

        RecordSessions.Node first = nodes.get(0);
        assertEquals("Connor McCracken", first.contributor());
        assertEquals("CM", first.initials());
        assertEquals("tissue outline and stain check", first.title());
        assertEquals("Connor McCracken", first.validator());
        assertTrue(first.signed());
        assertTrue(first.stamped());
        assertTrue(first.certified());
        assertEquals(RecordSessions.Tone.HIGH, first.tone());
        assertEquals("2026-10-23", first.date());
    }

    @Test
    void withoutNotesASessionIsCalledByItsNumberAndNeverByItsScope() {
        RecordSessions.Node second = RecordSessions.nodes(GRAPH, 0).get(1);
        assertEquals("Session #2", second.title());
        assertFalse(second.signed());
        assertFalse(second.certified());
        assertEquals("R", second.initials());
        // A degraded classifier outweighs the confidence, as on the record page.
        assertEquals(RecordSessions.Tone.DEGRADED, second.tone());
    }

    @Test
    void anUnstampedSessionHasNoValidatorAndAnUnknownAuthorWhenNoneIsRecorded() {
        RecordSessions.Node third = RecordSessions.nodes(GRAPH, 0).get(2);
        assertFalse(third.stamped());
        assertNull(third.validator());
        assertEquals("unknown", third.contributor());
        assertEquals("U", third.initials());
        assertEquals(RecordSessions.Tone.NONE, third.tone());
    }

    @Test
    void anAuthorAlwaysGetsTheSameBadgeColour() {
        assertEquals(RecordSessions.hue("Connor McCracken"), RecordSessions.hue("Connor McCracken"));
        assertNotEquals(RecordSessions.hue("Connor McCracken"), RecordSessions.hue("Romain Tourte"));
    }

    @Test
    void anAuthorGetsTheSameBadgeColourAsOnTheRecordPage() {
        // commit-style.ts contributorColor: Math.abs(hash) % 360, hash = h * 31 + char.
        assertEquals(140, RecordSessions.hue("Connor McCracken"));
        assertEquals(72, RecordSessions.hue("Sofia Gentile"));   // negative hash
        assertEquals(90, RecordSessions.hue("romain"));
    }

    @Test
    void nothingReadableGivesNoNode() {
        assertEquals(List.of(), RecordSessions.nodes("not json", -1));
    }
}
