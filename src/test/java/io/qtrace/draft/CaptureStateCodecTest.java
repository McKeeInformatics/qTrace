package io.qtrace.draft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.AlignmentRecord;
import io.qtrace.CellIntensityRecord;
import io.qtrace.ClassifierRecord;
import io.qtrace.DisplaySettingsRecord;
import io.qtrace.ImportedObjectFileRecord;
import io.qtrace.MeasurementMapRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CaptureStateCodecTest {

    private static final Instant T = Instant.parse("2026-09-24T10:15:30.123Z");

    private static CaptureState fullState() {
        CaptureState s = new CaptureState();
        JsonObject step = new JsonObject();
        step.addProperty("command", "Cell detection");
        step.addProperty("script_fragment", "runPlugin('x', '{}')");
        step.addProperty("pre_tracking", false);
        s.capturedSteps.add(step);
        UUID ann = UUID.fromString("11111111-2222-3333-4444-555555555555");
        s.annotationStepIndex.put(ann, 0);
        s.manualAnnotationLatestIndex.put("55555555", 0);
        s.snapshotAnnotationIds.add(UUID.fromString("aaaaaaaa-2222-3333-4444-555555555555"));
        s.deletedFragments.add("removeObject(_obj_deadbeef)");
        JsonObject corr = new JsonObject();
        corr.addProperty("justification", "artefact");
        s.manualDetectionCorrections.add(corr);
        s.manualAnnotationCorrections.add(corr.deepCopy());
        s.pendingRemovedDetections.add(corr.deepCopy());
        s.pendingAddedDetections.add(corr.deepCopy());
        s.pendingRemovedAnnotations.add(corr.deepCopy());

        JsonArray features = new JsonArray(); features.add("Mean");
        ClassifierRecord cr = new ClassifierRecord("pc1", "{\"a\":1}", "abc", T, "romain", "imgsha",
            "ANN_MLP", "Classification", 0.5, features, new JsonArray(), new JsonArray(), new JsonArray(),
            null, 0.0, new JsonObject(), List.of(ann), "trainhash");
        cr.gitHash = "g1";
        cr.tpcFilePath = "TPC-1.json";
        cr.trainingImages.add("img.svs");
        cr.appliedAtStepOrder = 3;
        cr.modifiedAfterTraining = true;
        s.knownClassifiers.put("pc1", cr);

        JsonObject oc = new JsonObject(); oc.addProperty("name", "oc1");
        s.knownObjectClassifiers.add(oc);

        s.knownImportedFiles.put("regions.geojson", new ImportedObjectFileRecord("regions.geojson",
            "img.import_1.geojson", "/tmp/regions.geojson", new byte[]{1, 2, 3, (byte) 0xff}, "sha", 4, T, "romain"));
        s.currentAlignment = new AlignmentRecord(T, "romain", "warpy", "affine",
            new double[]{1, 0, 5, 0, 1, 6}, "row_major", "mh", "moving", "file:/m", "ref", "file:/r", "w.json", "1", "2");
        s.measurementMapRecords.add(new MeasurementMapRecord("Area", "Viridis", 0.0, 10.0, T));
        s.displaySettingsRecords.add(new DisplaySettingsRecord(
            List.of(new DisplaySettingsRecord.ChannelSetting("DAPI", 0x0000ff, 0f, 255f, true)), 1.2, false, true, T));
        s.cellIntensityRecords.put("Nucleus: DAB OD mean",
            new CellIntensityRecord("Nucleus: DAB OD mean", new double[]{0.2, 0.4}, T, "romain"));

        JsonObject rf = new JsonObject(); rf.addProperty("session_id", "src");
        s.replayedFrom = rf;
        s.lastKnownStepCount = 7;
        s.preExistingStepCount = 2;
        s.manualAnnotationCount = 1;
        s.importCounter = 1;
        s.imageHash = "imgsha";
        return s;
    }

    @Test
    void roundTripPreservesEveryField() {
        CaptureState in = fullState();
        JsonObject json = CaptureStateCodec.toJson(in);
        // Go through text, like the draft file does.
        CaptureState out = CaptureStateCodec.fromJson(JsonParser.parseString(json.toString()).getAsJsonObject());

        assertEquals(in.capturedSteps, out.capturedSteps);
        assertEquals(in.annotationStepIndex, out.annotationStepIndex);
        assertEquals(in.manualAnnotationLatestIndex, out.manualAnnotationLatestIndex);
        assertEquals(in.snapshotAnnotationIds, out.snapshotAnnotationIds);
        assertEquals(in.deletedFragments, out.deletedFragments);
        assertEquals(in.manualDetectionCorrections, out.manualDetectionCorrections);
        assertEquals(in.manualAnnotationCorrections, out.manualAnnotationCorrections);
        assertEquals(in.pendingRemovedDetections, out.pendingRemovedDetections);
        assertEquals(in.pendingAddedDetections, out.pendingAddedDetections);
        assertEquals(in.pendingRemovedAnnotations, out.pendingRemovedAnnotations);
        assertEquals(in.knownObjectClassifiers, out.knownObjectClassifiers);
        assertEquals(7, out.lastKnownStepCount);
        assertEquals(2, out.preExistingStepCount);
        assertEquals(1, out.manualAnnotationCount);
        assertEquals(1, out.importCounter);
        assertEquals("imgsha", out.imageHash);
        assertEquals(in.replayedFrom, out.replayedFrom);

        ClassifierRecord cr = out.knownClassifiers.get("pc1");
        assertEquals("{\"a\":1}", cr.jsonContent);
        assertEquals(T, cr.savedAt);
        assertEquals("g1", cr.gitHash);
        assertEquals("TPC-1.json", cr.tpcFilePath);
        assertEquals(List.of("img.svs"), cr.trainingImages);
        assertEquals(3, cr.appliedAtStepOrder);
        assertTrue(cr.modifiedAfterTraining);
        assertEquals(in.knownClassifiers.get("pc1").trainingAnnotationIds, cr.trainingAnnotationIds);
        assertEquals(in.knownClassifiers.get("pc1").features, cr.features);

        ImportedObjectFileRecord imp = out.knownImportedFiles.get("regions.geojson");
        assertArrayEquals(new byte[]{1, 2, 3, (byte) 0xff}, imp.content);
        assertEquals(T, imp.importedAt);

        assertArrayEquals(new double[]{1, 0, 5, 0, 1, 6}, out.currentAlignment.matrix);
        assertEquals("row_major", out.currentAlignment.matrixLayout);
        assertEquals("Viridis", out.measurementMapRecords.get(0).colormapName);
        DisplaySettingsRecord ds = out.displaySettingsRecords.get(0);
        assertEquals(in.displaySettingsRecords.get(0).channels, ds.channels);
        assertTrue(ds.invertBackground);
        assertArrayEquals(new double[]{0.2, 0.4},
            out.cellIntensityRecords.get("Nucleus: DAB OD mean").thresholds);
    }

    @Test
    void emptyStateRoundTrips() {
        CaptureState out = CaptureStateCodec.fromJson(CaptureStateCodec.toJson(new CaptureState()));
        assertTrue(out.capturedSteps.isEmpty());
        assertNull(out.currentAlignment);
    }

    @Test
    void unknownStateVersionIsRefused() {
        JsonObject json = CaptureStateCodec.toJson(fullState());
        json.addProperty("state_version", 999);
        assertThrows(IllegalArgumentException.class, () -> CaptureStateCodec.fromJson(json));
    }
}
