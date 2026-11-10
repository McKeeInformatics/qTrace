package io.qtrace.player;

import io.qtrace.player.PlayerAutoSave.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** When the player saves the image it is leaving: only a finished, healthy target, and only if asked. */
class PlayerAutoSaveTest {

    @Test
    void savesAFinishedHealthyTargetWhenTheBoxIsChecked() {
        assertThat(PlayerAutoSave.decide(true, true, false, false)).isEqualTo(Decision.SAVE);
    }

    @Test
    void doesNothingWhenTheBoxIsUnchecked() {
        assertThat(PlayerAutoSave.decide(false, true, false, false)).isEqualTo(Decision.OFF);
    }

    @Test
    void doesNothingWithoutAnImage() {
        assertThat(PlayerAutoSave.decide(true, false, false, false)).isEqualTo(Decision.NO_IMAGE);
    }

    @Test
    void doesNotSaveATargetWhoseStepsFailed() {
        assertThat(PlayerAutoSave.decide(true, true, true, false)).isEqualTo(Decision.TARGET_FAILED);
    }

    @Test
    void doesNotSaveWhenTheBatchWasInterrupted() {
        assertThat(PlayerAutoSave.decide(true, true, false, true)).isEqualTo(Decision.INTERRUPTED);
    }

    @Test
    void theBoxComesFirstSoAnUncheckedBoxIsNeverNoisy() {
        assertThat(PlayerAutoSave.decide(false, false, true, true)).isEqualTo(Decision.OFF);
    }
}
