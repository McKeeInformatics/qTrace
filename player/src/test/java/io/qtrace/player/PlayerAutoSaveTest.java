package io.qtrace.player;

import io.qtrace.player.PlayerAutoSave.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** When the player saves the image it is leaving: only a finished, healthy target, and only if asked. */
class PlayerAutoSaveTest {

    @Test
    void savesAFinishedHealthyTargetWhenTheBoxIsChecked() {
        assertThat(PlayerAutoSave.decide(true, true, true, false, false, true)).isEqualTo(Decision.SAVE);
    }

    @Test
    void doesNothingWhenTheBoxIsUnchecked() {
        assertThat(PlayerAutoSave.decide(false, true, true, false, false, true)).isEqualTo(Decision.OFF);
    }

    @Test
    void doesNothingWithoutAnImage() {
        assertThat(PlayerAutoSave.decide(true, false, false, false, false, true)).isEqualTo(Decision.NO_IMAGE);
    }

    @Test
    void neverSavesAnImageThatIsNotTheTargetJustPlayed() {
        // The target did not open (missing file, open timed out): the steps ran on whatever image
        // was on screen - saving it would write the replay into an image nobody asked for.
        assertThat(PlayerAutoSave.decide(true, true, false, false, false, true)).isEqualTo(Decision.NOT_TARGET);
    }

    @Test
    void doesNotSaveATargetWhoseStepsFailed() {
        assertThat(PlayerAutoSave.decide(true, true, true, true, false, false)).isEqualTo(Decision.TARGET_FAILED);
    }

    @Test
    void dropsTheWorkOfAFailedTargetWhenTheBatchMovesOn() {
        // Not worth keeping, and left unsaved it would make QuPath ask "Save changes?" and stop
        // the batch at the next image.
        assertThat(PlayerAutoSave.decide(true, true, true, true, false, true)).isEqualTo(Decision.DISCARD_FAILED);
    }

    @Test
    void leavesTheLastFailedTargetUnsavedOnScreenForTheUser() {
        assertThat(PlayerAutoSave.decide(true, true, true, true, false, false)).isEqualTo(Decision.TARGET_FAILED);
    }

    @Test
    void doesNotSaveWhenTheBatchWasInterrupted() {
        assertThat(PlayerAutoSave.decide(true, true, true, false, true, true)).isEqualTo(Decision.INTERRUPTED);
    }

    @Test
    void theBoxComesFirstSoAnUncheckedBoxIsNeverNoisy() {
        assertThat(PlayerAutoSave.decide(false, false, false, true, true, true)).isEqualTo(Decision.OFF);
    }
}
