package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A pixel classifier only loaded from the image's history is recorded as loaded, never as saved here. */
class ClassifierLoadedFlagTest {

    private static ClassifierRecord record() {
        return new ClassifierRecord("pc", "{}", "abc", Instant.parse("2026-11-06T20:00:00Z"), "u", "img",
            "ANN_MLP", "Classification", 0.5, new JsonArray(), new JsonArray(), new JsonArray(), new JsonArray(),
            null, 0.0, new JsonObject(), List.of(), "");
    }

    @Test
    void theExportMarksALoadedClassifier() {
        ClassifierRecord r = record();
        r.loadedOnly = true;
        assertTrue(QTraceExporter.classifierJson(r).get("loaded").getAsBoolean());
    }

    @Test
    void aTrainedClassifierCarriesNoFlag() {
        assertFalse(QTraceExporter.classifierJson(record()).has("loaded"));
    }
}
