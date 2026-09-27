package io.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTimelineTest {

    // ── summarize(): generic rule (Option B) ─────────────────────────────────

    @Test
    void genericKeepsArgumentValuesWithoutQuotes() {
        assertEquals("FLUORESCENCE", VersionTimeline.summarize("Set image type", "setImageType('FLUORESCENCE')"));
        assertEquals("true", VersionTimeline.summarize("Create full image annotation", "createFullImageAnnotation(true)"));
    }

    @Test
    void genericIsEmptyWithoutArguments() {
        assertEquals("", VersionTimeline.summarize("Delete selected objects", "removeSelectedObjects()"));
        assertEquals("", VersionTimeline.summarize("Anything", null));
        assertEquals("", VersionTimeline.summarize("Anything", "   "));
    }

    @Test
    void genericReducesPathsToTheirLastElement() {
        assertEquals("model.json · 2",
            VersionTimeline.summarize("x", "doSomething(\"C:/Users/jane/models/model.json\", 2)"));
    }

    @Test
    void genericDropsJsonBlobsAndShortensQualifiedClassNames() {
        String script = "runPlugin('qupath.imagej.detect.cells.PositiveCellDetection', "
            + "'{\"detectionImageBrightfield\":\"Hematoxylin OD\",\"singleThreshold\":0.2}')";
        assertEquals("PositiveCellDetection", VersionTimeline.summarize("Positive cell detection", script));
    }

    @Test
    void genericKeepsCommasInsideQuotedArguments() {
        assertEquals("a, b · 3", VersionTimeline.summarize("x", "f(\"a, b\", 3)"));
    }

    @Test
    void genericTruncatesLongSummaries() {
        String s = VersionTimeline.summarize("x", "f(\"" + "a".repeat(200) + "\")");
        assertTrue(s.length() <= VersionTimeline.MAX_SUMMARY, s);
        assertTrue(s.endsWith("…"), s);
    }

    // ── summarize(): dedicated rules (Option C) ──────────────────────────────

    @Test
    void instanSegSaysWhatItDetectsOnWhichChannels() {
        String script = "qupath.ext.instanseg.core.InstanSeg.builder()\n"
            + "    .modelPath(\"C:/PSP Qupath/downloaded/fluorescence_nuclei_and_cells-0.1.1\")\n"
            + "    .device(\"cpu\")\n"
            + "    .inputChannels([ColorTransforms.createChannelExtractor(\"Ir(193)_193Ir_DNA-2\"), "
            + "ColorTransforms.createChannelExtractor(\"Pt(196)_196Pt_ICSK2\")])\n"
            + "    .build()\n"
            + "    .detectObjects()";
        assertEquals("nuclei + cells · fluorescence 0.1.1 · DNA-2, ICSK2",
            VersionTimeline.summarize("Run InstanSeg model", script));
    }

    @Test
    void instanSegUnderstandsTheReplayForm() {
        String script = "InstanSeg.builder()\n"
            + "    .modelPath(_qtraceInstanSegPath(\"fluorescence_nuclei_and_cells\", "
            + "\"/home/jane/QuPath/v0.7/instanseg/downloaded/fluorescence_nuclei_and_cells-0.1.1\"))\n"
            + "    .inputChannels([ColorTransforms.createChannelExtractor(0)])\n"
            + "    .build()";
        assertEquals("nuclei + cells · fluorescence 0.1.1 · ch 0", VersionTimeline.summarize("InstanSeg.builder()", script));
    }

    @Test
    void instanSegNeverLeaksTheLocalPath() {
        String script = "qupath.ext.instanseg.core.InstanSeg.builder().modelPath(\"/home/jane/secret/brightfield_nuclei-0.1.0\").build()";
        String s = VersionTimeline.summarize("Run InstanSeg model", script);
        assertEquals("nuclei · brightfield 0.1.0", s);
        assertFalse(s.contains("home"));
    }

    @Test
    void stainVectorsShowEveryStainWithRoundedValues() {
        String script = "setColorDeconvolutionStains('{\"Name\" : \"H-DAB estimated\", \"Stain 1\" : \"Hematoxylin\", "
            + "\"Values 1\" : \"0.65111 0.70119 0.29049\", \"Stain 2\" : \"DAB\", "
            + "\"Values 2\" : \"0.26917 0.56824 0.77759\", \"Background\" : \" 255 255 255\"}')";
        assertEquals("H 0.651 0.701 0.290 · DAB 0.269 0.568 0.778",
            VersionTimeline.summarize("Set color deconvolution stains", script));
    }

    @Test
    void stainVectorsKeepUnknownStainNames() {
        String script = "setColorDeconvolutionStains('{\"Name\" : \"H&E\", \"Stain 1\" : \"Hematoxylin\", "
            + "\"Values 1\" : \"0.644 0.717 0.267\", \"Stain 2\" : \"Eosin\", \"Values 2\" : \"0.093 0.954 0.283\", "
            + "\"Stain 3\" : \"Residual\", \"Values 3\" : \"0.636 0.001 0.771\", \"Background\" : \" 255 255 255\"}')";
        String s = VersionTimeline.summarize("x", script);
        assertTrue(s.startsWith("H 0.644 0.717 0.267 · E 0.093 0.954 0.283 · Residual"), s);
        assertTrue(s.length() <= VersionTimeline.MAX_SUMMARY, s);
    }

    @Test
    void classifierSaysWhichOneAndWhatItProduces() {
        assertEquals("ANN-HR-TEST → annotations",
            VersionTimeline.summarize("Pixel classifier", "createAnnotationsFromPixelClassifier(\"ANN-HR-TEST\", 5.0, 3.0)"));
        assertEquals("ANN-HR-TEST → detections",
            VersionTimeline.summarize("Pixel classifier", "createDetectionsFromPixelClassifier(\"ANN-HR-TEST\", 5.0, 3.0)"));
        assertEquals("tumor_stroma → measurements",
            VersionTimeline.summarize("Load", "addPixelClassifierMeasurements(\"/tmp/cls/tumor_stroma.json\", \"tumor_stroma\")"));
        assertEquals("general tau → classifies detections",
            VersionTimeline.summarize("Object classifier", "classifyDetectionsByCentroid(\"general tau\")"));
        assertEquals("cells_v2 → classifies detections",
            VersionTimeline.summarize("Object classifier", "runObjectClassifier(\"cells_v2\")"));
    }

    // ── build(): steps, then the session's milestone ─────────────────────────

    private static JsonObject step(int order, String command, String ts, String script) {
        JsonObject s = new JsonObject();
        s.addProperty("order", order);
        s.addProperty("command", command);
        s.addProperty("timestamp", ts);
        s.addProperty("is_scriptable", script != null);
        if (script != null) s.addProperty("script_fragment", script);
        return s;
    }

    private static JsonObject root() {
        JsonObject s1 = new JsonObject();
        JsonArray steps1 = new JsonArray();
        steps1.add(step(1, "Set image type", "2026-09-27T09:14:02Z", "setImageType('BRIGHTFIELD_H_DAB')"));
        steps1.add(step(2, "Create full image annotation", "2026-09-27T09:16:11Z", "createFullImageAnnotation(true)"));
        s1.add("steps", steps1);
        s1.addProperty("exported_at", "2026-09-27T09:20:00Z");
        JsonObject val = JsonParser.parseString("{\"validator\":\"Jane Doe\",\"confidence\":\"High\","
            + "\"timestamp\":\"2026-09-27T09:19:30Z\",\"imageHash\":\"9c41aa00e07a9c41aa00e07a\"}").getAsJsonObject();
        s1.add("validation", val);
        s1.addProperty("validation_state", "stamped");

        JsonObject s2 = new JsonObject();
        JsonArray steps2 = new JsonArray();
        JsonObject inherited = step(1, "Set image type", "2026-09-27T09:14:02Z", "setImageType('BRIGHTFIELD_H_DAB')");
        inherited.addProperty("pre_tracking", true);
        steps2.add(inherited);
        JsonObject excluded = step(2, "Delete selected objects", "2026-09-27T10:01:00Z", "removeSelectedObjects()");
        excluded.addProperty("replay_excluded", true);
        steps2.add(excluded);
        s2.add("steps", steps2);
        s2.addProperty("exported_at", "2026-09-27T10:05:00Z");
        s2.add("validation", com.google.gson.JsonNull.INSTANCE);
        s2.addProperty("validation_state", "unstamped");

        JsonArray sessions = new JsonArray();
        sessions.add(s1);
        sessions.add(s2);
        JsonObject root = new JsonObject();
        root.add("sessions", sessions);
        return root;
    }

    @Test
    void buildInterleavesStepsAndOneMilestonePerSession() {
        List<VersionTimeline.Entry> entries = VersionTimeline.build(root(), ZoneOffset.UTC);
        assertEquals(6, entries.size());

        assertEquals(VersionTimeline.Kind.STEP, entries.get(0).kind());
        assertEquals("09:14:02", entries.get(0).time());
        assertEquals("Set image type", entries.get(0).command());
        assertEquals("BRIGHTFIELD_H_DAB", entries.get(0).summary());
        assertEquals(0, entries.get(0).sessionIndex());

        VersionTimeline.Entry stamp = entries.get(2);
        assertEquals(VersionTimeline.Kind.STAMP, stamp.kind());
        assertEquals(0, stamp.sessionIndex());
        assertEquals("09:19:30", stamp.time()); // stamp time, not export time

        assertEquals(VersionTimeline.Kind.UNSTAMPED, entries.get(5).kind());
        assertEquals(1, entries.get(5).sessionIndex());
        assertEquals("10:05:00", entries.get(5).time());
    }

    @Test
    void buildCarriesStepFlags() {
        List<VersionTimeline.Entry> entries = VersionTimeline.build(root(), ZoneOffset.UTC);
        assertTrue(entries.get(3).preTracking());
        assertFalse(entries.get(3).replayExcluded());
        assertTrue(entries.get(4).replayExcluded());
        assertFalse(entries.get(0).preTracking());
        assertEquals("removeSelectedObjects()", entries.get(4).script());
    }

    @Test
    void timeFollowsTheGivenZone() {
        List<VersionTimeline.Entry> entries = VersionTimeline.build(root(), ZoneOffset.ofHours(-4));
        assertEquals("05:14:02", entries.get(0).time());
    }

    @Test
    void footerCountsOnlyNonMilestoneStepsAndShortensTheHash() {
        JsonObject r = root();
        assertEquals(4, VersionTimeline.stepCount(VersionTimeline.build(r, ZoneOffset.UTC)));
        assertEquals("9c41…e07a", VersionTimeline.shortHash(r));
    }

    @Test
    void buildToleratesMissingSessions() {
        assertTrue(VersionTimeline.build(new JsonObject(), ZoneOffset.UTC).isEmpty());
        assertEquals("", VersionTimeline.shortHash(new JsonObject()));
    }

    // ── build(): qTrace-captured records (pixel training, alignment) ─────────

    private static JsonObject classifier(String name, String savedAt) {
        return JsonParser.parseString("{\"name\":\"" + name + "\",\"saved_at\":\"" + savedAt + "\","
            + "\"classifier_type\":\"ANN_MLP\",\"resolution_um\":8.0,\"n_channels\":46,"
            + "\"classes\":[\"Ignore*\",\"Normal Brain\",\"aSMA +\",\"general Tau\"],"
            + "\"n_training_regions\":12,\"fidelity\":\"HIGH\"}").getAsJsonObject();
    }

    @Test
    void pixelTrainingAppearsOnceAtItsTimeInTheSessionThatFirstHasIt() {
        JsonObject r = root();
        JsonArray pcs1 = new JsonArray();
        pcs1.add(classifier("tumor_stroma", "2026-09-27T09:15:00Z"));
        r.getAsJsonArray("sessions").get(0).getAsJsonObject().add("pixel_classifiers", pcs1);
        JsonArray pcs2 = new JsonArray();   // cumulative block: same record again, never shown twice
        pcs2.add(classifier("tumor_stroma", "2026-09-27T09:15:00Z"));
        r.getAsJsonArray("sessions").get(1).getAsJsonObject().add("pixel_classifiers", pcs2);

        List<VersionTimeline.Entry> entries = VersionTimeline.build(r, ZoneOffset.UTC);
        assertEquals(7, entries.size());
        VersionTimeline.Entry t = entries.get(1);   // between 09:14:02 and 09:16:11
        assertEquals(VersionTimeline.Kind.STEP, t.kind());
        assertEquals(VersionTimeline.Source.PIXEL_TRAINING, t.source());
        assertEquals("Train pixel classifier", t.command());
        assertEquals("09:15:00", t.time());
        assertEquals("tumor_stroma · 4 classes · 8 µm/px · ANN_MLP", t.summary());
        assertTrue(t.details().toString().contains("Normal Brain"), t.details().toString());
        assertTrue(t.details().toString().contains("12"), t.details().toString());
        assertEquals(VersionTimeline.Source.WORKFLOW, entries.get(0).source());
    }

    @Test
    void warpyAlignmentSaysWhichImageWasAlignedOnWhich() {
        JsonObject r = root();
        JsonObject al = JsonParser.parseString("{\"applied\":true,\"capture_source\":\"WarpyFile\","
            + "\"detected_at\":\"2026-09-27T10:02:00Z\",\"transform_type\":\"affine\","
            + "\"moving_image_name\":\"HE_07.ome.tif\",\"reference_image_name\":\"IHC_07.ome.tif\","
            + "\"scale_x\":1.02,\"scale_y\":1.02,\"translate_x\":120.4,\"translate_y\":-35.0}").getAsJsonObject();
        r.getAsJsonArray("sessions").get(1).getAsJsonObject().add("alignment", al);

        List<VersionTimeline.Entry> entries = VersionTimeline.build(r, ZoneOffset.UTC);
        VersionTimeline.Entry a = entries.stream()
            .filter(e -> e.source() == VersionTimeline.Source.ALIGNMENT).findFirst().orElseThrow();
        assertEquals("Align image (Warpy)", a.command());
        assertEquals("HE_07.ome.tif → IHC_07.ome.tif · affine", a.summary());
        assertEquals("10:02:00", a.time());
        assertEquals(1, a.sessionIndex());
        assertTrue(a.details().toString().contains("120.4"), a.details().toString());
        assertEquals(VersionTimeline.Kind.UNSTAMPED, entries.get(entries.size() - 1).kind());
    }

    @Test
    void unappliedAlignmentIsNotShown() {
        JsonObject r = root();
        r.getAsJsonArray("sessions").get(0).getAsJsonObject()
            .add("alignment", JsonParser.parseString("{\"applied\":false}"));
        assertEquals(6, VersionTimeline.build(r, ZoneOffset.UTC).size());
    }
}
