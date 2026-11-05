package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Real classifier files, written by QuPath 0.7.0 (ObjectClassifiers.writeClassifier) - see src/test/resources/object-classifiers. */
class ObjectClassifierContentTest {

    static String fixture(String name) throws IOException {
        try (var in = ObjectClassifierContentTest.class.getResourceAsStream("/object-classifiers/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aThresholdClassifierKeepsItsMeasurementThresholdAndClasses() throws Exception {
        JsonObject s = ObjectClassifierContent.summarize(fixture("OC-CK19-thr"));
        assertEquals("threshold", s.get("kind").getAsString());
        assertEquals("CELLS", s.get("filter").getAsString());
        assertEquals("Cell: CK19 mean", s.get("measurement").getAsString());
        assertEquals(120.0, s.get("threshold").getAsDouble());
        assertEquals("CK19-", s.get("below").getAsString());
        assertEquals("CK19+", s.get("equals").getAsString());
        assertEquals("CK19+", s.get("above").getAsString());
    }

    @Test
    void aFractionalThresholdIsNotRounded() throws Exception {
        assertEquals(200.25, ObjectClassifierContent.summarize(fixture("OC-CD20-thr")).get("threshold").getAsDouble());
        assertEquals(80.5, ObjectClassifierContent.summarize(fixture("OC-CD3-thr")).get("threshold").getAsDouble());
    }

    @Test
    void aCompositeListsItsComponentsInOrderEachInFull() throws Exception {
        JsonObject s = ObjectClassifierContent.summarize(fixture("OC-Panel6-composite"));
        assertEquals("composite", s.get("kind").getAsString());
        var components = s.getAsJsonArray("components");
        assertEquals(6, components.size());
        List<String> measurements = new ArrayList<>();
        List<Double> thresholds = new ArrayList<>();
        for (var c : components) {
            JsonObject o = c.getAsJsonObject();
            assertEquals("threshold", o.get("kind").getAsString());
            measurements.add(o.get("measurement").getAsString());
            thresholds.add(o.get("threshold").getAsDouble());
        }
        assertEquals(List.of("Cell: CK19 mean", "Cell: CD3 mean", "Cell: CD8 mean", "Cell: CD20 mean",
            "Cell: Ki67 mean", "Cell: PanCK mean"), measurements);
        assertEquals(List.of(120.0, 80.5, 64.0, 200.25, 45.0, 150.0), thresholds);
    }

    @Test
    void aCompositeNamesEveryClassItCanAssign() throws Exception {
        var classes = ObjectClassifierContent.summarize(fixture("OC-Panel6-composite")).getAsJsonArray("classes");
        List<String> names = new ArrayList<>();
        classes.forEach(c -> names.add(c.getAsString()));
        assertTrue(names.containsAll(List.of("CK19+", "CK19-", "PanCK+", "PanCK-")));
        assertEquals(12, names.size());
    }

    @Test
    void aTrainedClassifierKeepsItsModelMeasurementsAndClassesButNotItsTrees() throws Exception {
        JsonObject s = ObjectClassifierContent.summarize(fixture("OC-Trained-rtrees"));
        assertEquals("trained", s.get("kind").getAsString());
        assertEquals("CELLS", s.get("filter").getAsString());
        assertEquals("RTrees", s.get("model").getAsString());
        assertEquals("[\"Cell: CK19 mean\",\"Cell: CD3 mean\"]", s.getAsJsonArray("measurements").toString());
        assertEquals("[\"Neg\",\"Pos\"]", s.getAsJsonArray("classes").toString());
        JsonObject params = s.getAsJsonObject("model_params");
        assertEquals(50, params.get("ntrees").getAsInt());
        assertEquals(25, params.get("max_depth").getAsInt());
        assertEquals(10, params.get("min_sample_count").getAsInt());
        assertFalse(params.has("trees"), "the fitted trees are in the file, not in the summary");
        assertTrue(s.toString().length() < 1000, "the summary stays small");
    }

    @Test
    void anUnknownClassifierTypeIsNamedNotGuessed() {
        JsonObject s = ObjectClassifierContent.summarize("{\"object_classifier_type\":\"FutureClassifier\",\"x\":1}");
        assertEquals("unknown", s.get("kind").getAsString());
        assertEquals("FutureClassifier", s.get("type").getAsString());
    }

    @Test
    void aFileThatIsNotAClassifierNeverThrows() {
        assertEquals("unreadable", ObjectClassifierContent.summarize("not json at all").get("kind").getAsString());
        assertEquals("unreadable", ObjectClassifierContent.summarize("").get("kind").getAsString());
        assertEquals("unreadable", ObjectClassifierContent.summarize(null).get("kind").getAsString());
        assertEquals("unreadable", ObjectClassifierContent.summarize("[1,2]").get("kind").getAsString());
    }

    @Test
    void aThresholdIsDescribedInOneReadableLine() throws Exception {
        String text = ObjectClassifierContent.describe(ObjectClassifierContent.summarize(fixture("OC-CK19-thr")));
        assertEquals("Cell: CK19 mean >= 120 -> CK19+, else CK19-", text);
        assertEquals("Cell: CD20 mean >= 200.25 -> CD20+, else CD20-",
            ObjectClassifierContent.describe(ObjectClassifierContent.summarize(fixture("OC-CD20-thr"))));
    }

    @Test
    void aCompositeAndATrainedClassifierAreDescribedToo() throws Exception {
        assertEquals("composite of 6: Cell: CK19 mean >= 120, Cell: CD3 mean >= 80.5, Cell: CD8 mean >= 64, "
            + "Cell: CD20 mean >= 200.25, Cell: Ki67 mean >= 45, Cell: PanCK mean >= 150",
            ObjectClassifierContent.describe(ObjectClassifierContent.summarize(fixture("OC-Panel6-composite"))));
        assertEquals("RTrees on Cell: CK19 mean, Cell: CD3 mean -> Neg, Pos",
            ObjectClassifierContent.describe(ObjectClassifierContent.summarize(fixture("OC-Trained-rtrees"))));
    }

    @Test
    void describingSomethingUnreadableSaysSo() {
        assertEquals("unreadable classifier file", ObjectClassifierContent.describe(ObjectClassifierContent.summarize("nope")));
        assertEquals("unknown classifier type FutureClassifier",
            ObjectClassifierContent.describe(ObjectClassifierContent.summarize("{\"object_classifier_type\":\"FutureClassifier\"}")));
    }

    @Test
    void theHeadlineIsTheRuleInFewWords() throws Exception {
        assertEquals("Cell: CK19 mean >= 120", ObjectClassifierContent.headline(ObjectClassifierContent.summarize(fixture("OC-CK19-thr"))));
        assertEquals("composite of 6", ObjectClassifierContent.headline(ObjectClassifierContent.summarize(fixture("OC-Panel6-composite"))));
        assertEquals("RTrees on 2 measurements", ObjectClassifierContent.headline(ObjectClassifierContent.summarize(fixture("OC-Trained-rtrees"))));
        assertEquals("unknown type FutureClassifier",
            ObjectClassifierContent.headline(ObjectClassifierContent.summarize("{\"object_classifier_type\":\"FutureClassifier\"}")));
        assertEquals("unreadable file", ObjectClassifierContent.headline(ObjectClassifierContent.summarize("nope")));
        assertEquals("", ObjectClassifierContent.headline(null), "an old record has no content to name");
    }

    @Test
    void aRecordIsHeadlinedByItsContent() throws Exception {
        byte[] raw = fixture("OC-CD20-thr").getBytes(StandardCharsets.UTF_8);
        var rec = ObjectClassifierFiles.record("OC-CD20-thr", raw, java.time.Instant.now(), "u", true);
        assertEquals("Cell: CD20 mean >= 200.25", ObjectClassifierContent.headline(rec.getAsJsonObject("content")));
    }

    // ── a trained classifier whose features are normalized or projected: QuPath wraps the extractor ──

    @Test
    void aNormalizedClassifierStillNamesItsMeasurements() throws Exception {
        JsonObject s = ObjectClassifierContent.summarize(fixture("OC-Trained-minmax"));
        assertEquals("[\"Cell: CK19 mean\",\"Cell: CD3 mean\",\"Cell: Ki67 mean\"]", s.getAsJsonArray("measurements").toString());
        assertEquals("[\"normalization\"]", s.getAsJsonArray("preprocessing").toString());
        assertEquals("RTrees on 3 measurements", ObjectClassifierContent.headline(s));
    }

    @Test
    void aProjectedClassifierStillNamesItsMeasurements() throws Exception {
        JsonObject s = ObjectClassifierContent.summarize(fixture("OC-Trained-pca"));
        assertEquals(3, s.getAsJsonArray("measurements").size());
        assertEquals("[\"pca\"]", s.getAsJsonArray("preprocessing").toString());
    }

    @Test
    void preprocessingIsListedInTheOrderItIsApplied() throws Exception {
        JsonObject s = ObjectClassifierContent.summarize(fixture("OC-Trained-minmax-pca"));
        assertEquals(3, s.getAsJsonArray("measurements").size());
        assertEquals("[\"normalization\",\"pca\"]", s.getAsJsonArray("preprocessing").toString());
        assertEquals("RTrees on Cell: CK19 mean, Cell: CD3 mean, Cell: Ki67 mean (normalization, pca) -> Neg, Pos", ObjectClassifierContent.describe(s));
    }

    @Test
    void aPlainTrainedClassifierHasNoPreprocessing() throws Exception {
        JsonObject s = ObjectClassifierContent.summarize(fixture("OC-Trained-rtrees"));
        assertEquals(0, s.getAsJsonArray("preprocessing").size());
    }

    @Test
    void theFittedNormalizerAndProjectionAreNotCopiedIntoTheSummary() throws Exception {
        assertTrue(ObjectClassifierContent.summarize(fixture("OC-Trained-minmax-pca")).toString().length() < 1500);
    }
}
