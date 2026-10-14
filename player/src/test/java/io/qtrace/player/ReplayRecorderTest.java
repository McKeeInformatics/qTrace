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

import io.qtrace.ActionLogger;
import io.qtrace.QTraceExporter;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.plugins.workflow.ScriptableWorkflowStep;
import qupath.lib.plugins.workflow.Workflow;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule: replaying a .qtrace of N instructions on a target image leaves that target with a
 * .qtrace whose session holds exactly the instructions that ran OK — failures excluded,
 * QuPath's own auto-steps on first open excluded, prior history kept only if the image had
 * some, and the source recorded as {@code replayed_from}.
 */
class ReplayRecorderTest {

    private ImageData<BufferedImage> imageData;
    private ActionLogger logger;
    private ReplayRecorder recorder;

    @BeforeEach
    void setUp() {
        imageData = new ImageData<>(null);
        logger = new ActionLogger(null, null);
    }

    private void attachAndStart(boolean keepHistory) {
        logger.attach(imageData);
        recorder = new ReplayRecorder(logger, imageData::getHistoryWorkflow);
        JsonObject from = new JsonObject();
        from.addProperty("source", "source.qtrace");
        from.addProperty("sha256", "abc123");
        recorder.start(from, keepHistory);
    }

    private static ReplayStep step(int i) {
        return new ReplayStep(i, "Script", "Instruction " + i, "println('step " + i + "')");
    }

    /** Runs one step through the recorder: {@code body} simulates what the Groovy did to the workflow. */
    private void play(ReplayStep s, ReplayExecutor.Status status, Consumer<Workflow> body) {
        recorder.beforeStep(s);
        body.accept(imageData.getHistoryWorkflow());
        recorder.afterStep(s, status);
    }

    private List<JsonObject> sessionSteps() {
        return QTraceExporter.selectSessionSteps(logger.getCapturedSteps());
    }

    @Test
    void tenOkInstructionsOnAVirginImage_giveExactlyTenSteps() {
        attachAndStart(false);
        for (int i = 1; i <= 10; i++) play(step(i), ReplayExecutor.Status.OK, wf -> {});

        assertThat(sessionSteps()).hasSize(10);
        assertThat(sessionSteps()).allMatch(j -> j.has("replayed_step"));
        assertThat(sessionSteps().get(0).get("script_fragment").getAsString()).isEqualTo("println('step 1')");
        assertThat(sessionSteps().get(9).get("command").getAsString()).isEqualTo("Instruction 10");
        assertThat(imageData.getHistoryWorkflow().size()).isEqualTo(10);
    }

    @Test
    void failedInstructionIsNotRecorded() {
        attachAndStart(false);
        for (int i = 1; i <= 10; i++) {
            var status = i == 4 ? ReplayExecutor.Status.FAILED : ReplayExecutor.Status.OK;
            // The failing step had already pushed a workflow entry before throwing.
            play(step(i), status, wf -> wf.addStep(new DefaultScriptableWorkflowStep("partial", "x()")));
        }
        assertThat(sessionSteps()).hasSize(9);
        assertThat(sessionSteps()).noneMatch(j -> j.get("replayed_step").getAsInt() == 4);
    }

    @Test
    void nativeWorkflowStepIsKeptAsIs_notDuplicated() {
        attachAndStart(false);
        play(step(1), ReplayExecutor.Status.OK,
            wf -> wf.addStep(new DefaultScriptableWorkflowStep("Cell detection", "runPlugin('x', '{}')")));

        assertThat(sessionSteps()).hasSize(1);
        assertThat(sessionSteps().get(0).get("command").getAsString()).isEqualTo("Cell detection");
    }

    @Test
    void instructionPushingSeveralWorkflowEntries_countsAsOne() {
        attachAndStart(false);
        play(step(1), ReplayExecutor.Status.OK, wf -> {
            wf.addStep(new DefaultScriptableWorkflowStep("a", "a()"));
            wf.addStep(new DefaultScriptableWorkflowStep("b", "b()"));
        });
        assertThat(sessionSteps()).hasSize(1);
        assertThat(((ScriptableWorkflowStep) imageData.getHistoryWorkflow().getLastStep()).getScript())
            .isEqualTo("println('step 1')");
    }

    @Test
    void quPathAutoStepsOnFirstOpen_areExcluded() {
        // QuPath sets the image type on first open — before and/or after qTrace attaches.
        imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Set image type", "setImageType('FLUORESCENCE')"));
        logger.attach(imageData);
        imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Estimate stain vectors", "setColorDeconvolutionStains('{}')"));
        recorder = new ReplayRecorder(logger, imageData::getHistoryWorkflow);
        recorder.start(new JsonObject(), false);

        for (int i = 1; i <= 3; i++) play(step(i), ReplayExecutor.Status.OK, wf -> {});
        assertThat(sessionSteps()).hasSize(3);
    }

    @Test
    void existingHistoryIsKept_andReplayAppends() {
        imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Earlier work", "earlier()"));
        attachAndStart(true);
        for (int i = 1; i <= 10; i++) play(step(i), ReplayExecutor.Status.OK, wf -> {});

        assertThat(sessionSteps()).hasSize(11);
        assertThat(sessionSteps().get(0).get("command").getAsString()).isEqualTo("Earlier work");
    }

    @Test
    void unsavedWorkOnTheOpenImageIsKept_whenHistoryIsKept() {
        logger.attach(imageData);
        imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Work before replay", "before()"));
        recorder = new ReplayRecorder(logger, imageData::getHistoryWorkflow);
        recorder.start(new JsonObject(), true);
        play(step(1), ReplayExecutor.Status.OK, wf -> {});

        assertThat(sessionSteps()).hasSize(2);
    }

    @Test
    void preambleWorkflowEntriesAreNotInstructions() {
        attachAndStart(false);
        recorder.beforePreamble();
        imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Set image type", "setImageType('BRIGHTFIELD_H_E')"));
        recorder.afterPreamble();
        play(step(1), ReplayExecutor.Status.OK, wf -> {});

        assertThat(sessionSteps()).hasSize(1);
    }

    @Test
    void replayedFromIsAvailableForTheExport() {
        attachAndStart(false);
        assertThat(logger.getReplayedFrom()).isNotNull();
        assertThat(logger.getReplayedFrom().get("sha256").getAsString()).isEqualTo("abc123");
    }

    @Test
    void manualWorkAfterTheReplayIsStillRecorded() {
        attachAndStart(false);
        play(step(1), ReplayExecutor.Status.OK, wf -> {});
        imageData.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Manual follow-up", "followUp()"));

        assertThat(sessionSteps()).hasSize(2);
    }
}
