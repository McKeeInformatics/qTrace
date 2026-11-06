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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * QuPath's script functions (runPlugin...) catch what a plugin throws and only log it; the step then ends normally.
 * The Player must not report such a step as done.
 */
class ReplayExecutorErrorsTest {

    private ReplayExecutor executor;

    @BeforeEach
    void start() throws Exception {
        executor = new ReplayExecutor(getClass().getClassLoader(), line -> {});
        executor.executePreamble("").get();
    }

    @AfterEach
    void stop() { executor.close(); }

    private ReplayExecutor.StepResult run(String groovy) throws Exception {
        return executor.executeStep(new ReplayStep(1, "Script", "t", groovy)).get();
    }

    @Test
    void aStepWhoseScriptFunctionLoggedAnErrorFails() throws Exception {
        var r = run("org.slf4j.LoggerFactory.getLogger('qupath.lib.gui.scripting.QPEx')"
            + ".error('Error running plugin WatershedCellDetection: WatershedCellDetection')");
        assertThat(r.status()).isEqualTo(ReplayExecutor.Status.FAILED);
        assertThat(r.errorMessage()).contains("Error running plugin WatershedCellDetection");
    }

    @Test
    void aStepThatLogsNothingWrongIsDone() throws Exception {
        var r = run("org.slf4j.LoggerFactory.getLogger('qupath.lib.gui.scripting.QPEx').info('all fine'); 1 + 1");
        assertThat(r.status()).isEqualTo(ReplayExecutor.Status.OK);
        assertThat(r.errorMessage()).isNull();
    }

    @Test
    void anErrorLoggedByAnotherComponentDoesNotFailTheStep() throws Exception {
        var r = run("org.slf4j.LoggerFactory.getLogger('some.other.library').error('noise')");
        assertThat(r.status()).isEqualTo(ReplayExecutor.Status.OK);
    }

    @Test
    void theErrorOfOneStepDoesNotFailTheNext() throws Exception {
        run("org.slf4j.LoggerFactory.getLogger('qupath.lib.gui.scripting.QPEx').error('Error running plugin X: boom')");
        assertThat(run("1 + 1").status()).isEqualTo(ReplayExecutor.Status.OK);
    }
}
