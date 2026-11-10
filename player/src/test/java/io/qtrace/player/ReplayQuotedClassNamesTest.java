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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A class name inside a string is data, not code: a recorded {@code runPlugin('qupath...WatershedCellDetection', ...)}
 * must reach QuPath with the full name (QuPath loads the plugin by it), while the use of a class in code is still
 * written by its simple name (the variable the preamble loads it into).
 */
class ReplayQuotedClassNamesTest {

    private static final Map<String, String> NAMES = Map.of(
        "qupath.imagej.detect.cells.WatershedCellDetection", "WatershedCellDetection",
        "qupath.ext.stardist.StarDist2D", "StarDist2D");

    private static String simplify(String fragment) {
        return QTraceReplayEngine.simplifyQualifiedNames(fragment, NAMES);
    }

    @Test
    void aPluginNamedInSingleQuotesKeepsItsFullName() {
        String f = "runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', '{\"threshold\":10.0}')";
        assertThat(simplify(f)).isEqualTo(f);
    }

    @Test
    void aPluginNamedInDoubleQuotesKeepsItsFullName() {
        String f = "runPlugin(\"qupath.imagej.detect.cells.WatershedCellDetection\", \"{}\")";
        assertThat(simplify(f)).isEqualTo(f);
    }

    @Test
    void aTripleQuotedStringKeepsItsContent() {
        String f = "println('''qupath.ext.stardist.StarDist2D\nand qupath.ext.stardist.StarDist2D again''')";
        assertThat(simplify(f)).isEqualTo(f);
    }

    @Test
    void aQuoteEscapedInsideAStringDoesNotEndIt() {
        String f = "println('it\\'s qupath.ext.stardist.StarDist2D')";
        assertThat(simplify(f)).isEqualTo(f);
    }

    @Test
    void theUseOfAClassInCodeIsStillWrittenByItsSimpleName() {
        assertThat(simplify("def m = qupath.ext.stardist.StarDist2D.builder('model').build()"))
            .isEqualTo("def m = StarDist2D.builder('model').build()");
    }

    @Test
    void onALineWithBothOnlyTheCodeChanges() {
        assertThat(simplify("def c = qupath.ext.stardist.StarDist2D; println('uses qupath.ext.stardist.StarDist2D')"))
            .isEqualTo("def c = StarDist2D; println('uses qupath.ext.stardist.StarDist2D')");
    }

    @Test
    void anImportLineIsLeftAsWritten() {
        String f = "import qupath.ext.stardist.StarDist2D\nprintln(StarDist2D)";
        assertThat(simplify(f)).isEqualTo(f);
    }

    @Test
    void anApostropheInACommentDoesNotOpenAString() {
        assertThat(simplify("// it's the cell detection\ndef c = qupath.ext.stardist.StarDist2D"))
            .isEqualTo("// it's the cell detection\ndef c = StarDist2D");
    }

    @Test
    void aRecordedCellDetectionStepReachesTheExecutorWithTheFullPluginName() {
        JsonObject step = new JsonObject();
        step.addProperty("command", "Cell detection");
        step.addProperty("is_scriptable", true);
        step.addProperty("script_fragment",
            "runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', '{\"threshold\":10.0}')");
        JsonArray steps = new JsonArray();
        steps.add(step);
        JsonObject session = new JsonObject();
        session.add("steps", steps);
        JsonArray sessions = new JsonArray();
        sessions.add(session);
        JsonObject root = new JsonObject();
        root.add("image", new JsonObject());
        root.add("sessions", sessions);

        List<ReplayStep> replay = QTraceReplayEngine.extractSteps(root.toString(), -1);

        assertThat(replay).hasSize(1);
        assertThat(replay.get(0).groovyFragment())
            .contains("runPlugin('qupath.imagej.detect.cells.WatershedCellDetection'");
    }

    // A blank line followed by an import line used to stop the scan for ever: the Player showed
    // "Loading…" with nothing in QuPath's log. Fails by timeout if the scan does not advance.
    @Test
    @org.junit.jupiter.api.Timeout(10)
    void aBlankLineBeforeAnImportLineDoesNotHangTheScan() {
        String f = "def a = 1\n\nimport qupath.ext.stardist.StarDist2D\nStarDist2D.builder()\n\n   \nimport x.Y\nrun(qupath.ext.stardist.StarDist2D)";
        assertThat(simplify(f)).isEqualTo(
            "def a = 1\n\nimport qupath.ext.stardist.StarDist2D\nStarDist2D.builder()\n\n   \nimport x.Y\nrun(StarDist2D)");
    }
}
