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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayStepTest {

    private static final String FIXTURE = """
        {
          "image": { "name": "test.tiff", "sha256": "abc" },
          "sessions": [
            {
              "steps": [
                { "script_fragment": "print('hello')", "is_scriptable": true },
                { "script_fragment": "createAnnotationsFromPixelClassifier('tissue', 1, 1)", "is_scriptable": true },
                { "script_fragment": "print('hello')", "is_scriptable": true },
                { "script_fragment": "not scriptable", "is_scriptable": false },
                { "script_fragment": "deleted step", "is_scriptable": true, "deleted": true }
              ]
            }
          ]
        }
        """;

    @Test
    void extractSteps_dedupsAndNumbersSequentially() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(FIXTURE, -1);

        // "print('hello')" appears twice — global dedup keeps last occurrence only,
        // so only 2 distinct steps remain (not-scriptable and deleted are excluded).
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).index()).isEqualTo(1);
        assertThat(steps.get(1).index()).isEqualTo(2);
    }

    @Test
    void extractSteps_classifiesPixelClassifierStep() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(FIXTURE, -1);
        ReplayStep pixelStep = steps.stream()
            .filter(s -> s.groovyFragment().contains("createAnnotationsFromPixelClassifier"))
            .findFirst().orElseThrow();
        assertThat(pixelStep.type()).isEqualTo("Pixel Classifier");
    }

    @Test
    void extractSteps_plainScriptDefaultsToScriptType() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(FIXTURE, -1);
        ReplayStep scriptStep = steps.stream()
            .filter(s -> s.groovyFragment().contains("print"))
            .findFirst().orElseThrow();
        assertThat(scriptStep.type()).isEqualTo("Script");
    }

    @Test
    void generateGroovyScript_stillEmitsStepMarkersMatchingExtractSteps() {
        List<ReplayStep> steps = QTraceReplayEngine.extractSteps(FIXTURE, -1);
        String script = QTraceReplayEngine.generateGroovyScript(FIXTURE, null, -1);

        for (ReplayStep step : steps) {
            assertThat(script).contains("[QTrace] Step " + step.index() + " start:");
        }
    }

    @Test
    void classify_segmentationModel() {
        assertThat(ReplayStep.classify("def d = InstanSeg.builder().build()")).isEqualTo("Segmentation");
        assertThat(ReplayStep.classify("runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', '{}')"))
            .isEqualTo("Cell detection");
        assertThat(ReplayStep.classify("print('nothing special')")).isEqualTo("Script");
    }
}
