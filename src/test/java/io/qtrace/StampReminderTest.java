package io.qtrace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When leaving an image asks "wasn't stamped - stamp it now?". */
class StampReminderTest {

    @Test
    void remindsWhenLeavingNewWorkThatWasNeverStamped() {
        assertTrue(StampReminder.needed(false, false, true, true, true, false));
    }

    @Test
    void anImageStampedAsItIsNowNeedsNoReminder() {
        assertFalse(StampReminder.needed(false, false, true, true, true, true));
    }

    @Test
    void aBatchThatDrivesTheImagesHoldsTheReminder() {
        // A Player batch of two or more Targets opens the images itself: a question would stop it
        // until someone answers. The batch says, image by image, what it stamped and what it did not.
        assertFalse(StampReminder.needed(true, false, true, true, true, false));
    }

    @Test
    void dontAskAgainAndTheSettingSilenceIt() {
        assertFalse(StampReminder.needed(false, true, true, true, true, false));
        assertFalse(StampReminder.needed(false, false, false, true, true, false));
    }

    @Test
    void nothingToRemindWithoutAnImageOrWithoutNewWork() {
        assertFalse(StampReminder.needed(false, false, true, false, true, false));
        assertFalse(StampReminder.needed(false, false, true, true, false, false));
    }

    @Test
    void stampedMeansTheSameImageWithNoChangeSinceTheStamp() {
        assertTrue(StampReminder.stampedAsItIs("h1", "h1", 7, 7));
        assertFalse(StampReminder.stampedAsItIs("h1", "h1", 7, 8));   // worked on after the stamp
        assertFalse(StampReminder.stampedAsItIs("h1", "h2", 7, 7));   // the stamp is another image's
        assertFalse(StampReminder.stampedAsItIs(null, "h1", 7, 7));
        assertFalse(StampReminder.stampedAsItIs("h1", null, 7, 7));   // never stamped
        assertFalse(StampReminder.stampedAsItIs("h1", "h1", -1, -1)); // a stamp whose moment is unknown
    }
}
