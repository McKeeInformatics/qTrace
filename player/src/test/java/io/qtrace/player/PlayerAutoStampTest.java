package io.qtrace.player;

import io.qtrace.player.PlayerAutoStamp.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** When a batch stamps the image it just played: two or more Targets, a clean run, and only if asked. */
class PlayerAutoStampTest {

    @Test
    void stampsATargetOfABatchThatRanCleanly() {
        assertThat(PlayerAutoStamp.decide(true, 2, true, true, false)).isEqualTo(Decision.STAMP);
        assertThat(PlayerAutoStamp.decide(true, 40, true, true, false)).isEqualTo(Decision.STAMP);
    }

    @Test
    void anUncheckedBoxNeverStamps() {
        assertThat(PlayerAutoStamp.decide(false, 5, true, true, false)).isEqualTo(Decision.OFF);
    }

    @Test
    void aSingleTargetIsNeverStampedAutomatically() {
        // One image is stamped by hand with Stamp, as after any replay: only a batch stamps itself.
        assertThat(PlayerAutoStamp.decide(true, 1, true, true, false)).isEqualTo(Decision.SINGLE_TARGET);
        assertThat(PlayerAutoStamp.decide(true, 0, true, true, false)).isEqualTo(Decision.SINGLE_TARGET);
    }

    @Test
    void neverStampsAnImageThatIsNotTheTargetJustPlayed() {
        assertThat(PlayerAutoStamp.decide(true, 3, false, true, false)).isEqualTo(Decision.NOT_TARGET);
    }

    @Test
    void anImageWithNothingRecordedHasNothingToStamp() {
        assertThat(PlayerAutoStamp.decide(true, 3, true, false, false)).isEqualTo(Decision.NO_STEPS);
    }

    @Test
    void neverValidatesATargetWhoseStepsFailed() {
        assertThat(PlayerAutoStamp.decide(true, 3, true, true, true)).isEqualTo(Decision.TARGET_FAILED);
    }

    @Test
    void theBoxComesFirstThenTheTargetCount() {
        assertThat(PlayerAutoStamp.decide(false, 1, false, false, true)).isEqualTo(Decision.OFF);
        assertThat(PlayerAutoStamp.decide(true, 1, false, false, true)).isEqualTo(Decision.SINGLE_TARGET);
    }
}
