package io.qtrace;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectionScopeTest {

    private static final String CLASSIFY = "createAnnotationsFromPixelClassifier(\"Trevor-ANN-1\", 0.0, 0.0)";
    private static final SelectionScope.Target RECT = new SelectionScope.Target(
        "0b6a1f6e-3c1d-4c7e-9a52-1d2f3e4a5b6c", "Rectangle", null, null,
        "POLYGON ((10 10, 50 10, 50 40, 10 40, 10 10))");

    @Test
    void commandsThatRunOnTheSelectionAreRecognised() {
        assertTrue(SelectionScope.dependsOnSelection(CLASSIFY));
        assertTrue(SelectionScope.dependsOnSelection("createDetectionsFromPixelClassifier(\"c\", 0.0, 0.0, \"SPLIT\")"));
        assertTrue(SelectionScope.dependsOnSelection("addPixelClassifierMeasurements(\"c\", \"c\")"));
        assertTrue(SelectionScope.dependsOnSelection("runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', '{}')"));
        assertTrue(SelectionScope.dependsOnSelection("addShapeMeasurements(\"AREA\")"));
        assertTrue(SelectionScope.dependsOnSelection("mergeSelectedAnnotations()"));
        assertTrue(SelectionScope.dependsOnSelection("def a = getSelectedObject()\nprintln a"));
        assertTrue(SelectionScope.dependsOnSelection("qupath.ext.instanseg.core.InstanSeg.builder()\n    .build()\n    .detectObjects()"));
    }

    @Test
    void commandsThatDoNotAreLeftAlone() {
        assertFalse(SelectionScope.dependsOnSelection(null));
        assertFalse(SelectionScope.dependsOnSelection("selectAnnotations();"));
        assertFalse(SelectionScope.dependsOnSelection("resetSelection();"));
        assertFalse(SelectionScope.dependsOnSelection("setImageType('FLUORESCENCE');"));
        // Deleting what is selected: a deleted manual annotation is already out of the replay.
        assertFalse(SelectionScope.dependsOnSelection("clearSelectedObjects(true);"));
        assertFalse(SelectionScope.dependsOnSelection("removeSelectedObjects()"));
    }

    @Test
    void selectionCommandsSetTheSelectionThemselves() {
        assertTrue(SelectionScope.setsSelection("selectAnnotations();"));
        assertTrue(SelectionScope.setsSelection("selectDetections();"));
        assertTrue(SelectionScope.setsSelection("resetSelection();"));
        assertTrue(SelectionScope.setsSelection("selectObjectsByClassification(\"Tumor\");"));
        assertTrue(SelectionScope.setsSelection("selectObjectsByClass(qupath.lib.objects.PathAnnotationObject);"));
        assertFalse(SelectionScope.setsSelection(CLASSIFY));
        assertFalse(SelectionScope.setsSelection(null));
    }

    @Test
    void theScopedFragmentSelectsTheSameObjectsThenRunsTheCommandUnchanged() {
        String scoped = SelectionScope.scoped(CLASSIFY, List.of(RECT));
        assertTrue(scoped.startsWith(SelectionScope.MARKER), scoped);
        assertTrue(scoped.endsWith("\n" + CLASSIFY), "QuPath's own command comes last, unchanged");
        assertTrue(scoped.contains("'" + RECT.id() + "'"));
        assertTrue(scoped.contains(RECT.wkt()), "the geometry finds it when its ID is not on the image");
        assertTrue(scoped.contains("setSelectedObjects("));
        assertTrue(scoped.contains("throw new IllegalStateException"), "never runs on the wrong objects");
    }

    @Test
    void aFragmentIsScopedOnce() {
        String scoped = SelectionScope.scoped(CLASSIFY, List.of(RECT));
        assertTrue(SelectionScope.isScoped(scoped));
        assertFalse(SelectionScope.isScoped(CLASSIFY));
        assertEquals(scoped, SelectionScope.scoped(scoped, List.of(RECT)));
    }

    @Test
    void twoScopedFragmentsCanShareOneScript() {
        // Groovy: one flat scope for the whole replay script — the variables must not collide.
        String a = SelectionScope.scoped(CLASSIFY, List.of(RECT));
        String b = SelectionScope.scoped("addPixelClassifierMeasurements(\"c\", \"c\")", List.of(RECT));
        var suffix = java.util.regex.Pattern.compile("_qtsel_([0-9a-f]{8})");
        var ma = suffix.matcher(a); var mb = suffix.matcher(b);
        assertTrue(ma.find() && mb.find());
        assertNotEquals(ma.group(1), mb.group(1));
        // The same command on the same objects: the same text (the replay keeps it once).
        assertEquals(a, SelectionScope.scoped(CLASSIFY, List.of(RECT)));
    }

    @Test
    void quotesInANameCannotBreakTheScript() {
        var named = new SelectionScope.Target("id-1", "Polygon", "Tumor 'A' \\ 1", "Tumor", null);
        String scoped = SelectionScope.scoped(CLASSIFY, List.of(named));
        assertTrue(scoped.contains("Tumor \\'A\\' \\\\ 1"), scoped);
        assertTrue(scoped.contains("'wkt': null"), "no geometry: by its ID only");
    }

    @Test
    void theStepSaysWhatItRanOn() {
        JsonArray a = SelectionScope.toJson(List.of(RECT));
        assertEquals(1, a.size());
        assertEquals(RECT.id(), a.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("Rectangle", a.get(0).getAsJsonObject().get("roi_type").getAsString());
        assertFalse(a.get(0).getAsJsonObject().has("wkt"), "the geometry is in the script, not twice");
    }
}
