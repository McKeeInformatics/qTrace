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

import com.google.gson.JsonObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.plugins.workflow.ScriptableWorkflowStep;
import qupath.lib.plugins.workflow.Workflow;
import qupath.lib.plugins.workflow.WorkflowStep;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Makes a replay leave a faithful trace on its target image: every instruction that runs OK
 * becomes exactly one step in the target's history workflow (and so in its .qtrace), and
 * nothing else does.
 *
 * Replayed Groovy doesn't reliably write to the workflow itself — {@code runPlugin} does,
 * display settings or a plain {@code addObject} don't, and some calls push several entries —
 * so after each instruction the entries it pushed are reconciled: an OK instruction keeps its
 * single native scriptable entry, or gets one synthesized from its fragment; a failed one
 * leaves none. {@link ActionLogger} defers capture meanwhile and tags the result
 * {@code replayed_step}.
 */
class ReplayRecorder {

    private final ActionLogger logger;
    private final Supplier<Workflow> workflow;
    private int sizeBefore;

    ReplayRecorder(ActionLogger logger, Supplier<Workflow> workflow) {
        this.logger = logger;
        this.workflow = workflow;
    }

    /**
     * Call once the target image is open, before its first instruction.
     * {@code keepHistory}: the target already had saved data — its history stays in the
     * session; false for a never-opened image, whose only steps must be the replayed ones.
     */
    void start(JsonObject replayedFrom, boolean keepHistory) {
        logger.markReplayStart(replayedFrom, keepHistory);
    }

    /** The preamble (imports, classifier restoration, ...) is setup, not an instruction. */
    void beforePreamble() {
        sizeBefore = workflow.get().size();
        logger.beginReplayStep(0);
    }

    void afterPreamble() {
        workflow.get().removeSteps(pushedSince(sizeBefore));
        logger.endReplayStep();
    }

    void beforeStep(ReplayStep step) {
        sizeBefore = workflow.get().size();
        logger.beginReplayStep(step.index());
    }

    void afterStep(ReplayStep step, ReplayExecutor.Status status) {
        Workflow wf = workflow.get();
        List<WorkflowStep> pushed = pushedSince(sizeBefore);
        boolean keepNative = pushed.size() == 1 && pushed.get(0) instanceof ScriptableWorkflowStep;
        if (status != ReplayExecutor.Status.OK || !keepNative) wf.removeSteps(pushed);
        if (status == ReplayExecutor.Status.OK && !keepNative)
            wf.addStep(new DefaultScriptableWorkflowStep(step.title(), step.groovyFragment()));
        logger.endReplayStep();
    }

    private List<WorkflowStep> pushedSince(int size) {
        List<WorkflowStep> steps = workflow.get().getSteps();
        return size < steps.size() ? new ArrayList<>(steps.subList(size, steps.size())) : List.of();
    }
}
