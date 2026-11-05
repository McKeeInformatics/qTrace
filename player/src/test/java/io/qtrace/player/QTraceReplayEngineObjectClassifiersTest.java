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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.qtrace.ObjectClassifierFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The replay script restores the recorded object classifiers before its first step. */
class QTraceReplayEngineObjectClassifiersTest {

    private static final byte[] RAW = ("{\"object_classifier_type\":\"SimpleClassifier\",\"function\":{\"classifier_fun\":"
        + "\"ClassifyByMeasurementFunction\",\"measurement\":\"Cell: CK19 mean\",\"threshold\":120.0}}").getBytes(StandardCharsets.UTF_8);

    private static String qtrace(JsonObject rec) {
        JsonObject root = new JsonObject();
        root.add("image", new JsonObject());
        JsonObject session = new JsonObject();
        session.add("steps", new JsonArray());
        JsonArray oc = new JsonArray();
        oc.add(rec);
        session.add("object_classifiers", oc);
        JsonArray sessions = new JsonArray();
        sessions.add(session);
        root.add("sessions", sessions);
        return root.toString();
    }

    @Test
    void thePreambleRestoresTheVerifiedObjectClassifierBeforeAnyStep(@TempDir Path dir) throws Exception {
        JsonObject rec = ObjectClassifierFiles.record("OC-CK19", RAW, Instant.now(), "oscar", true);
        Path q = dir.resolve("slide.png.qtrace");
        Files.createDirectories(ObjectClassifierFiles.companionDir(q));
        Files.write(ObjectClassifierFiles.companionDir(q).resolve(rec.get("classifier_file").getAsString()), RAW);
        String json = qtrace(rec);
        Path temp = dir.resolve("temp");
        ObjectClassifierReplay.prepare(q.toString(), json, temp);

        String preamble = QTraceReplayEngine.buildPreambleScript(json, temp, -1);
        assertTrue(preamble.contains(Base64.getEncoder().encodeToString(RAW)));
        assertTrue(preamble.contains("Restore object classifier: OC-CK19"));
        assertFalse(preamble.contains("WARNING: object classifier"));
    }

    @Test
    void withoutItsFileThePreambleSaysThisReplayWillNotUseTheRecordedClassifier() {
        JsonObject rec = ObjectClassifierFiles.record("OC-CK19", RAW, Instant.now(), "oscar", true);
        String preamble = QTraceReplayEngine.buildPreambleScript(qtrace(rec), null, -1);
        assertTrue(preamble.contains("WARNING: object classifier \"OC-CK19\" cannot be restored"), preamble);
        assertTrue(preamble.contains("will NOT use the recorded classifier"));
    }

    @Test
    void aRecordWithoutObjectClassifiersGetsNoSuchLines() {
        JsonObject root = new JsonObject();
        root.add("image", new JsonObject());
        JsonArray sessions = new JsonArray();
        JsonObject session = new JsonObject();
        session.add("steps", new JsonArray());
        sessions.add(session);
        root.add("sessions", sessions);
        String preamble = QTraceReplayEngine.buildPreambleScript(root.toString(), null, -1);
        assertFalse(preamble.contains("object classifier"));
        assertEquals(-1, preamble.indexOf("Restore object classifier"));
    }
}
