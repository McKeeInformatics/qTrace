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

import com.google.gson.JsonObject;
import io.qtrace.ActionLogger;
import io.qtrace.QTraceExporter;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.ImageData;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;

/** A creation step as the Player runs it: what it did is told, logged, and never passed off as a user's instruction. */
class ObjectClassifierCreationRunTest {

    private static final String SHA = "a".repeat(64);

    private static ReplayStep creation(String fragment) {
        return new ReplayStep(2, "Object Classifier", "Create object classifier: OC", fragment, true, null, false,
            new ReplayStep.ObjectClassifierFile("OC", SHA, true));
    }

    @Test
    void whatTheStepDidIsKeptWithItsResult() throws Exception {
        ReplayExecutor executor = new ReplayExecutor(getClass().getClassLoader(), line -> {});
        try {
            executor.executePreamble("").get();
            var r = executor.executeStep(creation("'already in the project (identical file, nothing written)'")).get();
            assertThat(r.status()).isEqualTo(ReplayExecutor.Status.OK);
            assertThat(r.note()).isEqualTo("already in the project (identical file, nothing written)");
            // An instruction's value is not a note: runPlugin returns true, a script its last value
            var plain = executor.executeStep(new ReplayStep(3, "Script", "t", "'just a value'")).get();
            assertThat(plain.note()).isNull();
        } finally {
            executor.close();
        }
    }

    @Test
    void theLogSaysWhatTheCreationDid(@TempDir Path dir) throws Exception {
        try (ReplayLogWriter w = new ReplayLogWriter("rec", "h", "0.7.0", "ok", List.of(), null, null, null, dir)) {
            w.appendStep(creation("x"), new ReplayExecutor.StepResult(ReplayExecutor.Status.OK, null, 12, null,
                "replaced: the project's different file is kept as OC.json.replaced-1"));
            assertThat(Files.readString(w.logPath()))
                .contains("[Step 2] Object Classifier")
                .contains("OK")
                .contains("replaced: the project's different file is kept as OC.json.replaced-1");
        }
    }

    @Test
    void theNewTraceDoesNotCountTheCreationAsAnInstruction() {
        ImageData<BufferedImage> imageData = new ImageData<>(null);
        ActionLogger logger = new ActionLogger(null, null);
        logger.attach(imageData);
        ReplayRecorder recorder = new ReplayRecorder(logger, imageData::getHistoryWorkflow);
        JsonObject from = new JsonObject();
        from.addProperty("source", "source.qtrace");
        recorder.start(from, false);

        ReplayStep create = creation("'created'");
        recorder.beforeStep(create);
        imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("something", "x()"));
        recorder.afterStep(create, ReplayExecutor.Status.OK);

        assertThat(QTraceExporter.selectSessionSteps(logger.getCapturedSteps())).isEmpty();
        assertThat(imageData.getHistoryWorkflow().size()).isZero();
    }

    @Test
    void aPacketPlayedAfterTheOneThatCreatedTheSameFileDoesNotCreateItAgain() {
        String sha = io.qtrace.chain.Hashing.sha256Hex("{}".getBytes());
        String json = """
            { "kind": "workflow", "title": "Cells", "sessions": [
              { "session_id": "p1", "title": "Classify", "validation": null,
                "steps": [ { "command": "Run object classifier", "is_scriptable": true, "timestamp": "2026-11-06T10:03:00Z",
                             "script_fragment": "runObjectClassifier('cells')" } ],
                "object_classifiers": [ { "name": "cells", "saved_at": "2026-11-06T10:02:00Z", "sha256": "%s",
                                          "classifier_file": "cells@x.json" } ] },
              { "session_id": "p2", "title": "Again", "validation": null,
                "steps": [ { "command": "Run object classifier again", "is_scriptable": true, "timestamp": "2026-11-06T10:05:00Z",
                             "script_fragment": "runObjectClassifier('cells');" } ] } ] }""".formatted(sha);

        // Played alone, the second packet creates the classifier its instruction runs
        assertThat(WorkflowSource.playlist(json, List.of(1)).stream().map(ps -> ps.step().title()).toList())
            .containsExactly("Create object classifier: cells", "Run object classifier again");
        // Played after the first, it has nothing to create: the file is already there
        assertThat(WorkflowSource.playlist(json, List.of(0, 1)).stream().map(ps -> ps.step().title()).toList())
            .containsExactly("Create object classifier: cells", "Run object classifier", "Run object classifier again");
        assertThat(WorkflowSource.playlist(json, List.of(0, 1)).stream().map(ps -> ps.step().index()).toList())
            .containsExactly(1, 2, 3);
    }

    @Test
    void theRowSaysWhatTheCreationDid() {
        ReplayStep create = creation("x");
        java.util.function.Function<String, String> row = note -> QTraceReplayPlayer.doneText(create,
            new ReplayExecutor.StepResult(ReplayExecutor.Status.OK, null, 1, null, note));
        assertThat(row.apply("created from the record's copy")).isEqualTo("Created");
        assertThat(row.apply("already in the project (identical file, nothing written)")).isEqualTo("Already in project");
        assertThat(row.apply("replaced: the project's different file is kept as OC.json.replaced-1")).isEqualTo("Replaced, old kept");
        assertThat(QTraceReplayPlayer.doneText(new ReplayStep(1, "Script", "t", "x()"),
            new ReplayExecutor.StepResult(ReplayExecutor.Status.OK, null, 1, null, "created"))).isEqualTo("Execution completed");
    }
}
