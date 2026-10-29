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

package io.qtrace;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CaptureEventsTest {

    @AfterEach
    void clear() {
        CaptureEvents.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void aModuleIsToldWhatWasJustCaptured() {
        ModuleEntitlements.setForTest(Set.of("logbook"));
        List<CaptureEvents.Event> seen = new ArrayList<>();
        CaptureEvents.register("logbook", seen::add);
        JsonObject json = new JsonObject();
        json.addProperty("command", "Cell detection");
        CaptureEvents.captured(CaptureEvents.COMMAND, "Cell detection", json);
        assertEquals(1, seen.size());
        assertEquals(CaptureEvents.COMMAND, seen.get(0).kind());
        assertEquals("Cell detection", seen.get(0).text());
        assertEquals("{\"command\":\"Cell detection\"}", seen.get(0).json());
        assertNotNull(seen.get(0).at());
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeIsNotTold() {
        ModuleEntitlements.setForTest(Set.of("compliance"));
        List<CaptureEvents.Event> seen = new ArrayList<>();
        CaptureEvents.register("logbook", seen::add);
        CaptureEvents.captured(CaptureEvents.SCRIPT, "Script: a.groovy", null);
        assertTrue(seen.isEmpty());
    }

    @Test
    void aListenerThatFailsNeitherBreaksTheCaptureNorTheOthers() {
        ModuleEntitlements.setForTest(Set.of("logbook", "other"));
        List<CaptureEvents.Event> seen = new ArrayList<>();
        CaptureEvents.register("logbook", e -> { throw new IllegalStateException("boom"); });
        CaptureEvents.register("other", seen::add);
        assertDoesNotThrow(() -> CaptureEvents.captured(CaptureEvents.IMPORT, "rois.geojson", null));
        assertEquals(1, seen.size());
    }

    @Test
    void theRecentCapturesAreKeptForAWindowOpenedLater() {
        for (int i = 0; i < CaptureEvents.MAX_RECENT + 5; i++)
            CaptureEvents.captured(CaptureEvents.COMMAND, "step " + i, null);
        List<CaptureEvents.Event> recent = CaptureEvents.recent();
        assertEquals(CaptureEvents.MAX_RECENT, recent.size());
        assertEquals("step 5", recent.get(0).text());
        assertEquals("step " + (CaptureEvents.MAX_RECENT + 4), recent.get(recent.size() - 1).text());
    }

    @Test
    void aResetForgetsTheRecentCaptures() {
        CaptureEvents.captured(CaptureEvents.COMMAND, "step", null);
        CaptureEvents.forgetRecent();
        assertTrue(CaptureEvents.recent().isEmpty());
    }

    @Test
    void aWorkflowStepIsSortedByWhatMadeIt() {
        assertEquals(CaptureEvents.ANNOTATION, CaptureEvents.kindOfStep("Manual annotation: Polygon"));
        assertEquals(CaptureEvents.SCRIPT, CaptureEvents.kindOfStep("Script: stardist.groovy"));
        assertEquals(CaptureEvents.IMPORT, CaptureEvents.kindOfStep("Import objects from file: rois.geojson"));
        assertEquals(CaptureEvents.COMMAND, CaptureEvents.kindOfStep("Cell detection"));
        assertEquals(CaptureEvents.COMMAND, CaptureEvents.kindOfStep(null));
    }

    @Test
    void nothingIsAnnouncedWithoutAKindOrAText() {
        ModuleEntitlements.setForTest(Set.of("logbook"));
        List<CaptureEvents.Event> seen = new ArrayList<>();
        CaptureEvents.register("logbook", seen::add);
        CaptureEvents.captured(null, "x", null);
        CaptureEvents.captured(CaptureEvents.COMMAND, null, null);
        assertTrue(seen.isEmpty());
        assertTrue(CaptureEvents.recent().isEmpty());
    }

    @Test
    void aCorrectionIsShownWithoutTheObjectsItLists() {
        JsonObject record = new JsonObject();
        record.addProperty("type", "deletion");
        record.addProperty("n_deleted", 3);
        record.add("deleted", new com.google.gson.JsonArray());
        assertEquals("{\"type\":\"deletion\",\"n_deleted\":3}", CaptureEvents.scalars(record).toString());
    }

    @Test
    void aModuleIsToldWhenALongActionStartsAndEnds() {
        ModuleEntitlements.setForTest(Set.of("logbook"));
        List<CaptureEvents.Activity> seen = new ArrayList<>();
        CaptureEvents.registerActivity("logbook", seen::add);
        CaptureEvents.started(CaptureEvents.SCRIPT, "Script running");
        assertEquals(List.of(CaptureEvents.SCRIPT), List.copyOf(CaptureEvents.running()));
        CaptureEvents.finished(CaptureEvents.SCRIPT);
        assertTrue(CaptureEvents.running().isEmpty());
        assertEquals(2, seen.size());
        assertTrue(seen.get(0).running());
        assertFalse(seen.get(1).running());
        assertEquals(CaptureEvents.SCRIPT, seen.get(1).kind());
    }

    @Test
    void aFinishWithoutAStartTellsNobody() {
        ModuleEntitlements.setForTest(Set.of("logbook"));
        List<CaptureEvents.Activity> seen = new ArrayList<>();
        CaptureEvents.registerActivity("logbook", seen::add);
        CaptureEvents.finished(CaptureEvents.COMMAND);
        assertTrue(seen.isEmpty());
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeIsNotToldOfActivity() {
        ModuleEntitlements.setForTest(Set.of("compliance"));
        List<CaptureEvents.Activity> seen = new ArrayList<>();
        CaptureEvents.registerActivity("logbook", seen::add);
        CaptureEvents.started(CaptureEvents.COMMAND, "Cell detection");
        assertTrue(seen.isEmpty());
    }
}
