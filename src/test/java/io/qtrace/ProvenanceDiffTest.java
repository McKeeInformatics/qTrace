package io.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.ProvenanceDiff.Finding;
import io.qtrace.ProvenanceDiff.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvenanceDiffTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject ann(String uuid, String cls, double cx, double cy, double area) {
        JsonObject a = new JsonObject();
        a.addProperty("uuid", uuid);
        a.addProperty("class", cls);
        a.addProperty("roi_type", "Polygon");
        a.addProperty("centroid_x", cx);
        a.addProperty("centroid_y", cy);
        a.addProperty("area_px2", area);
        return a;
    }

    private static JsonArray arr(JsonObject... objs) {
        JsonArray a = new JsonArray();
        for (JsonObject o : objs) a.add(o);
        return a;
    }

    // ── .qtrace session vs stamped payload ──────────────────────────────────

    @Test
    void reportsTheExactEditedField() {
        JsonObject stamped = json("{\"validation\":{\"statusLabel\":\"1-In Progress\",\"validator\":\"Jane\"},\"steps_captured\":10}");
        JsonObject current = json("{\"validation\":{\"statusLabel\":\"2-Finished\",\"validator\":\"Jane\"},\"steps_captured\":10}");
        List<Finding> f = ProvenanceDiff.diffSession(stamped, current, "1-In Progress");
        assertEquals(1, f.size());
        assertEquals(Kind.FIELD, f.get(0).kind());
        assertEquals("validation.statusLabel", f.get(0).subject());
        assertEquals("\"1-In Progress\"", f.get(0).stamped());
        assertEquals("\"2-Finished\"", f.get(0).current());
    }

    @Test
    void ignoresExportTimestampAndTheCertReferenceAddedAfterStamping() {
        JsonObject stamped = json("{\"exported_at\":\"t1\",\"external_files\":[{\"type\":\"csv\",\"sha256\":\"x\"}]}");
        JsonObject current = json("{\"exported_at\":\"t2\",\"external_files\":[{\"type\":\"csv\",\"sha256\":\"x\"},"
            + "{\"type\":\"cert\",\"sha256\":\"y\"}]}");
        assertTrue(ProvenanceDiff.diffSession(stamped, current, null).isEmpty());
    }

    @Test
    void reportsATopLevelStatusThatDisagreesWithTheStamp() {
        JsonObject s = json("{\"validation\":{\"statusLabel\":\"1-In Progress\"}}");
        List<Finding> f = ProvenanceDiff.diffSession(s, s.deepCopy(), "2-Finished");
        assertEquals(1, f.size());
        assertEquals("status", f.get(0).subject());
    }

    @Test
    void rootStatusEditedTogetherWithTheStampIsReportedOnce() {
        JsonObject stamped = json("{\"validation\":{\"statusLabel\":\"1-In Progress\"}}");
        JsonObject current = json("{\"validation\":{\"statusLabel\":\"2-Finished\"}}");
        List<Finding> f = ProvenanceDiff.diffSession(stamped, current, "2-Finished");
        assertEquals(List.of("validation.statusLabel"), f.stream().map(Finding::subject).toList());
    }

    // ── annotations ─────────────────────────────────────────────────────────

    @Test
    void identicalAnnotationsYieldNoFinding() {
        JsonArray a = arr(ann("u1", "Tumor", 10, 20, 300));
        assertTrue(ProvenanceDiff.diffAnnotations(a, a.deepCopy()).isEmpty());
    }

    @Test
    void removedAndAddedAnnotations() {
        List<Finding> f = ProvenanceDiff.diffAnnotations(
            arr(ann("u1", "Tumor", 10, 20, 300)),
            arr(ann("u2", "Stroma", 500, 500, 50)));
        assertEquals(List.of(Kind.ANNOTATION_REMOVED, Kind.ANNOTATION_ADDED), f.stream().map(Finding::kind).toList());
    }

    @Test
    void sameGeometryUnderANewUuidIsRecreated() {
        List<Finding> f = ProvenanceDiff.diffAnnotations(
            arr(ann("u1", "Tumor", 10, 20, 300)),
            arr(ann("u9", "Tumor", 10.01, 20, 300)));
        assertEquals(1, f.size());
        assertEquals(Kind.ANNOTATION_RECREATED, f.get(0).kind());
    }

    @Test
    void recreatedAndMovedIsModified() {
        List<Finding> f = ProvenanceDiff.diffAnnotations(
            arr(ann("u1", "Tumor", 10, 20, 300)),
            arr(ann("u9", "Tumor", 70, 20, 600)));
        assertEquals(1, f.size());
        assertEquals(Kind.ANNOTATION_MODIFIED, f.get(0).kind());
        assertTrue(f.get(0).current().contains("+60"), f.get(0).current());
    }

    @Test
    void reclassifiedAnnotationKeepsItsUuid() {
        List<Finding> f = ProvenanceDiff.diffAnnotations(
            arr(ann("u1", "Tumor", 10, 20, 300)),
            arr(ann("u1", "Stroma", 10, 20, 300)));
        assertEquals(1, f.size());
        assertEquals(Kind.ANNOTATION_MODIFIED, f.get(0).kind());
        assertTrue(f.get(0).current().contains("Stroma"));
    }

    // ── detections ──────────────────────────────────────────────────────────

    @Test
    void sameDetectionFingerprintYieldsNoFinding() {
        JsonObject d = json("{\"total\":3,\"by_class\":{\"Tumor\":3},\"fingerprint_sha256\":\"f\"}");
        assertTrue(ProvenanceDiff.diffDetections(d, d.deepCopy()).isEmpty());
    }

    @Test
    void detectionChangesAreSummarisedPerClass() {
        JsonObject s = json("{\"total\":5,\"by_class\":{\"Tumor\":3,\"Stroma\":2},\"fingerprint_sha256\":\"f1\"}");
        JsonObject c = json("{\"total\":4,\"by_class\":{\"Tumor\":1,\"Stroma\":2,\"Immune\":1},\"fingerprint_sha256\":\"f2\"}");
        List<Finding> f = ProvenanceDiff.diffDetections(s, c);
        assertEquals(1, f.size());
        assertEquals(Kind.DETECTIONS, f.get(0).kind());
        assertEquals("5", f.get(0).stamped());
        assertTrue(f.get(0).current().startsWith("4"), f.get(0).current());
        assertTrue(f.get(0).current().contains("Tumor −2"), f.get(0).current());
        assertTrue(f.get(0).current().contains("Immune +1"), f.get(0).current());
    }

    @Test
    void sameCountsButDifferentFingerprintMeansObjectsChanged() {
        JsonObject s = json("{\"total\":3,\"by_class\":{\"Tumor\":3},\"fingerprint_sha256\":\"f1\"}");
        JsonObject c = json("{\"total\":3,\"by_class\":{\"Tumor\":3},\"fingerprint_sha256\":\"f2\"}");
        List<Finding> f = ProvenanceDiff.diffDetections(s, c);
        assertEquals(1, f.size());
        assertTrue(f.get(0).current().contains("moved or reshaped"), f.get(0).current());
    }

    @Test
    void stampWithoutDetectionFingerprintIsNotComparable() {
        List<Finding> f = ProvenanceDiff.diffDetections(null, json("{\"total\":3,\"by_class\":{},\"fingerprint_sha256\":\"f\"}"));
        assertEquals(1, f.size());
        assertEquals(Kind.NOT_COMPARABLE, f.get(0).kind());
    }

    // ── satellite files ─────────────────────────────────────────────────────

    private static JsonArray fileRef(Path f, String type) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("filename", f.getFileName().toString());
        o.addProperty("path", f.toString());
        o.addProperty("size_bytes", Files.size(f));
        o.addProperty("sha256", io.qtrace.chain.Hashing.sha256Hex(f));
        return arr(o);
    }

    @Test
    void unchangedSatelliteFileYieldsNoFinding(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("thumb.jpg"), "pixels");
        assertTrue(ProvenanceDiff.checkFiles(fileRef(f, "thumbnail"), dir).isEmpty());
    }

    @Test
    void modifiedSatelliteFileIsReported(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("thumb.jpg"), "pixels");
        JsonArray ref = fileRef(f, "thumbnail");
        Files.writeString(f, "PIXELS");
        List<Finding> out = ProvenanceDiff.checkFiles(ref, dir);
        assertEquals(1, out.size());
        assertEquals(Kind.FILE, out.get(0).kind());
        assertEquals("modified", out.get(0).current());
    }

    @Test
    void appendOnlyLogWithIntactPrefixIsNotReported(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("master_validation_log.csv"), "h\nrow1\n");
        JsonArray ref = fileRef(f, "csv");
        Files.writeString(f, "h\nrow1\nrow2\n");
        assertTrue(ProvenanceDiff.checkFiles(ref, dir).isEmpty());
    }

    @Test
    void appendOnlyLogWithRewrittenPrefixIsReported(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("master_validation_log.csv"), "h\nrow1\n");
        JsonArray ref = fileRef(f, "csv");
        Files.writeString(f, "h\nROW1\nrow2\n");
        assertEquals("modified", ProvenanceDiff.checkFiles(ref, dir).get(0).current());
    }

    @Test
    void missingSatelliteFileIsReported(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("thumb.jpg"), "pixels");
        JsonArray ref = fileRef(f, "thumbnail");
        Files.delete(f);
        assertEquals("missing", ProvenanceDiff.checkFiles(ref, dir).get(0).current());
    }

    @Test
    void geojsonIsCheckedAgainstItsStampedHash(@TempDir Path dir) throws Exception {
        Path g = Files.writeString(dir.resolve("a.geojson"), "{}");
        String sha = io.qtrace.chain.Hashing.sha256Hex(g);
        assertTrue(ProvenanceDiff.checkGeoJson("a.geojson", sha, dir).isEmpty());
        Files.writeString(g, "{\"features\":[]}");
        assertEquals(Kind.FILE, ProvenanceDiff.checkGeoJson("a.geojson", sha, dir).get(0).kind());
    }

    @Test
    void appendedBytesOnANonLogFileAreAModification(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("thumb.jpg"), "pixels");
        JsonArray ref = fileRef(f, "thumbnail");
        Files.writeString(f, "pixelsX");
        assertEquals("modified", ProvenanceDiff.checkFiles(ref, dir).get(0).current());
    }

    @Test
    void fileMissingFromTheTraceFolderIsMissingEvenIfTheStampedAbsolutePathExists(@TempDir Path dir) throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("original"));
        Path f = Files.writeString(elsewhere.resolve("thumb.jpg"), "pixels");
        Path traceDir = Files.createDirectories(dir.resolve("trace"));
        assertEquals("missing", ProvenanceDiff.checkFiles(fileRef(f, "thumbnail"), traceDir).get(0).current());
    }

    @Test
    void longTextFieldsShowOnlyTheChangedPassage() {
        String big = "x".repeat(2000);
        JsonObject stamped = new JsonObject(), current = new JsonObject();
        stamped.addProperty("script", big + "model-0.1.1\")" + big);
        current.addProperty("script", big + "model-0.1.0\")" + big);
        Finding f = ProvenanceDiff.diffSession(stamped, current, null).get(0);
        assertTrue(f.stamped().length() < 120, f.stamped());
        assertTrue(f.stamped().contains("0.1.1") && f.current().contains("0.1.0"), f.stamped() + " / " + f.current());
    }

    @Test
    void aRemovedStepIsOneFinding() {
        JsonObject stamped = json("{\"steps\":[{\"command\":\"A\",\"order\":1},{\"command\":\"B\",\"order\":2}]}");
        JsonObject current = json("{\"steps\":[{\"command\":\"A\",\"order\":1}]}");
        List<Finding> f = ProvenanceDiff.diffSession(stamped, current, null);
        assertEquals(1, f.size());
        assertEquals("steps[1]", f.get(0).subject());
        assertEquals("B", f.get(0).stamped());
        assertEquals("∅", f.get(0).current());
    }

    @Test
    void sharedLogFallsBackToItsStampedPathWhenTheTraceWasMovedWithoutIt(@TempDir Path dir) throws Exception {
        Path original = Files.createDirectories(dir.resolve("original"));
        Path log = Files.writeString(original.resolve("master_validation_log.csv"), "h\nrow1\n");
        JsonArray ref = fileRef(log, "csv");
        Path moved = Files.createDirectories(dir.resolve("moved"));
        Files.writeString(moved.resolve("master_validation_log.csv"), "h\nother\n");
        assertTrue(ProvenanceDiff.checkFiles(ref, moved).isEmpty());
        Files.writeString(log, "h\nROW1\n");
        assertEquals("modified", ProvenanceDiff.checkFiles(ref, moved).get(0).current());
    }
}
