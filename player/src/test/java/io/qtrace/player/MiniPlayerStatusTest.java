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

import io.qtrace.player.MiniPlayerStatus.Phase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The mini-player's status line: what runs now, and a closing message once the replay is over. */
class MiniPlayerStatusTest {

    private static MiniPlayerStatus base(Phase phase) {
        return new MiniPlayerStatus(phase, "qtc_01ABC", 13, 0, null, 1, 1, null, 0, 0, 0, 0);
    }

    @Test
    void empty_saysHowToStart() {
        assertThat(base(Phase.EMPTY).text()).isEqualTo("No replay loaded — 📂 to open a .qtrace file or a qtc_… ID");
    }

    @Test
    void ready_saysItIsReadyToPlay() {
        assertThat(base(Phase.READY).text()).isEqualTo("✓ Ready to play — qtc_01ABC · 13 instructions · ▶ play · ▶│ step by step");
    }

    @Test
    void loading_saysTheSourceIsLoading() {
        assertThat(base(Phase.LOADING).text()).isEqualTo("Loading the source .qtrace…");
    }

    @Test
    void running_showsTheStepBeingExecuted() {
        var s = new MiniPlayerStatus(Phase.RUNNING, "qtc_01ABC", 13, 3, "Cell detection", 1, 1, null, 0, 0, 0, 0);
        assertThat(s.text()).isEqualTo("⏳ Step 3/13 · Cell detection — running…");
    }

    @Test
    void running_inABatch_alsoShowsTheImage() {
        var s = new MiniPlayerStatus(Phase.RUNNING, "qtc_01ABC", 13, 3, "Cell detection", 2, 5, null, 0, 0, 0, 0);
        assertThat(s.text()).isEqualTo("⏳ Image 2/5 · Step 3/13 · Cell detection — running…");
    }

    @Test
    void failed_pausesAndSaysWhatToDo() {
        var s = new MiniPlayerStatus(Phase.FAILED, "qtc_01ABC", 13, 11, "createAnnotations…", 1, 1, "Classifier not found", 0, 0, 0, 0);
        assertThat(s.text()).isEqualTo("❌ Step 11/13 failed: Classifier not found — ▶ continue · ⤢ details");
    }

    @Test
    void paused_saysWhereItStopped() {
        var s = new MiniPlayerStatus(Phase.PAUSED, "qtc_01ABC", 13, 4, "selectAnnotations()", 1, 1, null, 0, 0, 0, 0);
        assertThat(s.text()).isEqualTo("⏸ Paused after step 4/13 · ▶ resume");
    }

    @Test
    void done_singleImage_countsWhatWasApplied() {
        var s = new MiniPlayerStatus(Phase.DONE, "qtc_01ABC", 13, 13, null, 1, 1, null, 10, 1, 2, 1);
        assertThat(s.text()).isEqualTo("✓ Replay finished — 10/13 instructions applied (1 failed, 2 unchecked) · .qtrace written");
    }

    @Test
    void done_allOk_staysShort() {
        var s = new MiniPlayerStatus(Phase.DONE, "qtc_01ABC", 13, 13, null, 1, 1, null, 13, 0, 0, 1);
        assertThat(s.text()).isEqualTo("✓ Replay finished — 13/13 instructions applied · .qtrace written");
    }

    @Test
    void done_batch_summarisesEveryImage() {
        var s = new MiniPlayerStatus(Phase.DONE, "qtc_01ABC", 13, 13, null, 3, 3, null, 36, 3, 0, 3);
        assertThat(s.text()).isEqualTo("✓ Replay finished on 3 images — 36/39 instructions applied (3 failed) · 3 .qtrace written");
    }

    @Test
    void done_withoutExport_doesNotClaimOne() {
        var s = new MiniPlayerStatus(Phase.DONE, "qtc_01ABC", 13, 13, null, 1, 1, null, 0, 0, 13, 0);
        assertThat(s.text()).isEqualTo("✓ Replay finished — 0/13 instructions applied (13 unchecked)");
    }

    @Test
    void loadError_isExplicit() {
        var s = new MiniPlayerStatus(Phase.LOAD_ERROR, null, 0, 0, null, 1, 1, "404 Not Found", 0, 0, 0, 0);
        assertThat(s.text()).isEqualTo("✕ Could not open the replay: 404 Not Found");
    }
}
