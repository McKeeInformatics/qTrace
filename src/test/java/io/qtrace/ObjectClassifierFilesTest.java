package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.qtrace.chain.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObjectClassifierFilesTest {

    private static final Instant T = Instant.parse("2026-11-04T10:00:00Z");

    private static byte[] ck19() throws Exception {
        return ObjectClassifierContentTest.fixture("OC-CK19-thr").getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject recordOf(byte[] raw) {
        return ObjectClassifierFiles.record("OC-CK19-thr", raw, T, "oscar", true);
    }

    @Test
    void aRecordKeepsTheFourFieldsItAlwaysHadPlusTheFingerprintAndTheContent() throws Exception {
        byte[] raw = ck19();
        JsonObject r = recordOf(raw);
        assertEquals("OC-CK19-thr", r.get("name").getAsString());
        assertEquals("2026-11-04T10:00:00Z", r.get("saved_at").getAsString());
        assertEquals("oscar", r.get("saved_by_user").getAsString());
        assertTrue(r.get("name_policy_valid").getAsBoolean());
        assertEquals(Hashing.sha256Hex(raw), r.get("sha256").getAsString());
        assertEquals("OC-CK19-thr@" + Hashing.sha256Hex(raw).substring(0, 12) + ".json", r.get("classifier_file").getAsString());
        JsonObject content = r.getAsJsonObject("content");
        assertEquals("threshold", content.get("kind").getAsString());
        assertEquals("Cell: CK19 mean", content.get("measurement").getAsString());
        assertEquals(120.0, content.get("threshold").getAsDouble());
    }

    @Test
    void aFileWeCannotReadStillGetsItsFingerprintAndItsCopy() {
        byte[] raw = "garbage".getBytes(StandardCharsets.UTF_8);
        JsonObject r = ObjectClassifierFiles.record("x", raw, T, "u", false);
        assertEquals("unreadable", r.getAsJsonObject("content").get("kind").getAsString());
        assertEquals(Hashing.sha256Hex(raw), r.get("sha256").getAsString());
        assertFalse(r.get("name_policy_valid").getAsBoolean());
    }

    @Test
    void theCopyIsNamedByWhatItHoldsSoTwoVersionsOfOneNameNeverCollide() {
        String a = ObjectClassifierFiles.fileName("OC", "aaaaaaaaaaaa1111");
        String b = ObjectClassifierFiles.fileName("OC", "bbbbbbbbbbbb2222");
        assertEquals("OC@aaaaaaaaaaaa.json", a);
        assertFalse(a.equals(b));
    }

    @Test
    void aNameCanNeverLeadOutOfTheFolder() {
        String f = ObjectClassifierFiles.fileName("../../etc/x\\y", "abcdef0123456789");
        assertFalse(f.contains("/") || f.contains("\\") || f.contains(".."), f);
        assertTrue(f.endsWith("@abcdef012345.json"));
    }

    @Test
    void theCompanionsOfARecordSitInAFolderBesideIt() {
        assertEquals(Path.of("/data/trace/slide.png.qtrace.files/object_classifiers"),
            ObjectClassifierFiles.companionDir(Path.of("/data/trace/slide.png.qtrace")));
    }

    @Test
    void keepCopyWritesTheExactBytesUnderTheirName(@TempDir Path dir) throws Exception {
        byte[] raw = ck19();
        Path copy = ObjectClassifierFiles.keepCopy(dir, recordOf(raw), raw);
        assertEquals(dir.resolve(recordOf(raw).get("classifier_file").getAsString()), copy);
        assertArrayEquals(raw, Files.readAllBytes(copy));
    }

    @Test
    void exportingCopiesEachKeptFileBesideTheQtraceAndReturnsHowMany(@TempDir Path dir) throws Exception {
        Path copies = dir.resolve("copies"), out = dir.resolve("trace");
        Files.createDirectories(out);
        byte[] a = ck19(), b = ObjectClassifierContentTest.fixture("OC-CD3-thr").getBytes(StandardCharsets.UTF_8);
        JsonObject ra = recordOf(a), rb = ObjectClassifierFiles.record("OC-CD3-thr", b, T, "oscar", true);
        ObjectClassifierFiles.keepCopy(copies, ra, a);
        ObjectClassifierFiles.keepCopy(copies, rb, b);
        JsonArray records = new JsonArray(); records.add(ra); records.add(rb);

        Path q = out.resolve("slide.png.qtrace");
        assertEquals(2, ObjectClassifierFiles.exportCompanions(records, copies, q));
        Path dest = ObjectClassifierFiles.companionDir(q);
        assertArrayEquals(a, Files.readAllBytes(dest.resolve(ra.get("classifier_file").getAsString())));
        assertArrayEquals(b, Files.readAllBytes(dest.resolve(rb.get("classifier_file").getAsString())));
    }

    @Test
    void aMissingKeptCopyIsSkippedNotFatalAndAnOldRecordWithoutContentIsLeftAlone(@TempDir Path dir) throws Exception {
        JsonObject old = new JsonObject(); old.addProperty("name", "OldOne");           // a .qtrace from before the capture
        JsonObject lost = recordOf(ck19());                                              // its copy was never kept
        JsonArray records = new JsonArray(); records.add(old); records.add(lost);
        assertEquals(0, ObjectClassifierFiles.exportCompanions(records, dir.resolve("none"), dir.resolve("a.qtrace")));
        assertFalse(Files.exists(ObjectClassifierFiles.companionDir(dir.resolve("a.qtrace"))));
    }

    @Test
    void verifyAcceptsTheExactFile(@TempDir Path dir) throws Exception {
        byte[] raw = ck19();
        JsonObject r = recordOf(raw);
        Path f = ObjectClassifierFiles.keepCopy(dir, r, raw);
        assertEquals(ObjectClassifierFiles.Status.OK, ObjectClassifierFiles.verify(r, f).status());
    }

    @Test
    void verifyReportsAFileThatIsGone(@TempDir Path dir) throws Exception {
        var c = ObjectClassifierFiles.verify(recordOf(ck19()), dir.resolve("nope.json"));
        assertEquals(ObjectClassifierFiles.Status.MISSING, c.status());
        assertTrue(c.reason().contains("nope.json"), c.reason());
    }

    @Test
    void verifyReportsAFileThatWasEditedAndSaysSo(@TempDir Path dir) throws Exception {
        byte[] raw = ck19();
        JsonObject r = recordOf(raw);
        Path f = ObjectClassifierFiles.keepCopy(dir, r, raw);
        Files.writeString(f, Files.readString(f).replace("120.0", "121.0"));       // the threshold moved
        var c = ObjectClassifierFiles.verify(r, f);
        assertEquals(ObjectClassifierFiles.Status.DIFFERS, c.status());
        assertTrue(c.reason().contains("sha256"), c.reason());
    }

    @Test
    void verifyCannotCheckARecordWrittenBeforeTheContentWasCaptured(@TempDir Path dir) throws Exception {
        JsonObject old = new JsonObject(); old.addProperty("name", "OldOne");
        var c = ObjectClassifierFiles.verify(old, dir.resolve("whatever.json"));
        assertEquals(ObjectClassifierFiles.Status.NOT_CAPTURED, c.status());
    }

    @Test
    void theLogbookLineNamesTheClassifierAndItsRule() throws Exception {
        assertEquals("Object classifier saved: OC-CK19-thr (Cell: CK19 mean >= 120)", ObjectClassifierFiles.eventText(recordOf(ck19())));
    }

    @Test
    void theLogbookLineOfAnOldOrUnreadableRecordIsJustItsName() {
        JsonObject old = new JsonObject(); old.addProperty("name", "OldOne");
        assertEquals("Object classifier saved: OldOne", ObjectClassifierFiles.eventText(old));
    }
}
