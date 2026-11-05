package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Which object classifier files an upload sends with a .qtrace: the ones it can vouch for. */
class ObjectClassifierPushTest {

    private static final Instant T = Instant.parse("2026-11-05T10:00:00Z");
    private static final byte[] V1 = bytes("Cell: CK19 mean", 120.0);
    private static final byte[] V2 = bytes("Cell: CK19 mean", 135.5);
    private static final byte[] CD3 = bytes("Cell: CD3 mean", 80.5);

    private static byte[] bytes(String m, double t) {
        return ("{\"object_classifier_type\":\"SimpleClassifier\",\"function\":{\"classifier_fun\":\"ClassifyByMeasurementFunction\",\"measurement\":\""
            + m + "\",\"threshold\":" + t + "}}").getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject rec(String name, byte[] raw) {
        return ObjectClassifierFiles.record(name, raw, T, "oscar", true);
    }

    /** A .qtrace whose sessions each saved the given records. */
    @SafeVarargs
    private static Path qtrace(Path dir, List<JsonObject>... perSession) throws Exception {
        JsonObject root = new JsonObject();
        JsonArray sessions = new JsonArray();
        for (List<JsonObject> recs : perSession) {
            JsonObject s = new JsonObject();
            JsonArray arr = new JsonArray();
            recs.forEach(arr::add);
            s.add("object_classifiers", arr);
            sessions.add(s);
        }
        root.add("sessions", sessions);
        Path q = dir.resolve("slide.png.qtrace");
        Files.writeString(q, root.toString());
        return q;
    }

    private static void beside(Path q, JsonObject rec, byte[] raw) throws Exception {
        Path d = ObjectClassifierFiles.companionDir(q);
        Files.createDirectories(d);
        Files.write(d.resolve(rec.get("classifier_file").getAsString()), raw);
    }

    @Test
    void everyVerifiedFileOfTheRecordIsSentOnce(@TempDir Path dir) throws Exception {
        JsonObject a = rec("OC-CK19", V1), b = rec("OC-CD3", CD3);
        Path q = qtrace(dir, List.of(a, b), List.of(a));            // the second session carries the first one's classifier again
        beside(q, a, V1);
        beside(q, b, CD3);
        var files = ObjectClassifierFiles.forPush(q, dir.resolve("copies"));
        assertEquals(List.of("OC-CK19", "OC-CD3"), files.stream().map(ObjectClassifierFiles.PushFile::name).toList());
        assertEquals(a.get("classifier_file").getAsString(), files.get(0).fileName());
        assertEquals(a.get("sha256").getAsString(), files.get(0).sha256());
        assertTrue(Files.isRegularFile(files.get(0).file()));
    }

    @Test
    void everyVersionOfAClassifierIsSentSoAnOlderSessionStaysReplayable(@TempDir Path dir) throws Exception {
        JsonObject v1 = rec("OC-CK19", V1), v2 = rec("OC-CK19", V2);
        Path q = qtrace(dir, List.of(v1), List.of(v2));
        beside(q, v1, V1);
        beside(q, v2, V2);
        assertEquals(2, ObjectClassifierFiles.forPush(q, dir.resolve("copies")).size());
    }

    @Test
    void aFileThatDoesNotMatchItsRecordedFingerprintIsNeverSent(@TempDir Path dir) throws Exception {
        JsonObject a = rec("OC-CK19", V1);
        Path q = qtrace(dir, List.of(a));
        beside(q, a, V2);                                           // another threshold under the recorded name
        assertTrue(ObjectClassifierFiles.forPush(q, dir.resolve("copies")).isEmpty());
    }

    @Test
    void theKeptCopyStandsInWhenTheFolderBesideTheRecordLacksTheFile(@TempDir Path dir) throws Exception {
        JsonObject a = rec("OC-CK19", V1);
        Path q = qtrace(dir, List.of(a));
        Path copies = dir.resolve("copies");
        ObjectClassifierFiles.keepCopy(copies, a, V1);
        var files = ObjectClassifierFiles.forPush(q, copies);
        assertEquals(1, files.size());
        assertEquals(copies.resolve(a.get("classifier_file").getAsString()), files.get(0).file());
    }

    @Test
    void aRecordFromBeforeTheContentWasCapturedSendsNothing(@TempDir Path dir) throws Exception {
        JsonObject old = new JsonObject();
        old.addProperty("name", "OldOne");
        assertTrue(ObjectClassifierFiles.forPush(qtrace(dir, List.of(old)), dir.resolve("copies")).isEmpty());
    }

    @Test
    void aMissingOrUnreadableRecordSendsNothing(@TempDir Path dir) throws Exception {
        assertTrue(ObjectClassifierFiles.forPush(dir.resolve("none.qtrace"), dir.resolve("copies")).isEmpty());
        Path bad = dir.resolve("bad.qtrace");
        Files.writeString(bad, "not json");
        assertTrue(ObjectClassifierFiles.forPush(bad, dir.resolve("copies")).isEmpty());
    }

    @Test
    void aNullRecordSendsNothing(@TempDir Path dir) {
        assertTrue(ObjectClassifierFiles.forPush(null, dir).isEmpty());
    }
}
