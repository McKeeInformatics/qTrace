package io.qtrace;

import com.google.gson.JsonObject;
import io.qtrace.draft.CaptureState;
import io.qtrace.draft.CaptureStateCodec;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** What makes an image worth a new unstamped session: work, not the logger's own bookkeeping. */
class DraftManagerWorkTest {

    private static CaptureState stateWithOneStep() {
        CaptureState s = new CaptureState();
        JsonObject step = new JsonObject();
        step.addProperty("command", "Cell detection");
        step.addProperty("script_fragment", "runPlugin('x', '{}')");
        s.capturedSteps.add(step);
        s.lastKnownStepCount = 1;
        return s;
    }

    private static String work(CaptureState s) {
        return DraftManager.workFingerprint(CaptureStateCodec.toJson(s));
    }

    @Test
    void bookkeepingAloneIsNotWork() {
        CaptureState before = stateWithOneStep();
        CaptureState after = stateWithOneStep();
        after.snapshotAnnotationIds.add(UUID.randomUUID());
        after.annotationStepIndex.put(UUID.randomUUID(), 0);
        after.manualAnnotationLatestIndex.put("abcd", 0);
        after.lastKnownStepCount = 7;
        after.preExistingStepCount = 7;
        after.imageHash = "f".repeat(64);
        assertEquals(work(before), work(after));
    }

    @Test
    void aNewInstructionIsWork() {
        CaptureState after = stateWithOneStep();
        JsonObject step = new JsonObject();
        step.addProperty("command", "Classify");
        after.capturedSteps.add(step);
        assertNotEquals(work(stateWithOneStep()), work(after));
    }

    @Test
    void aManualCorrectionIsWork() {
        CaptureState after = stateWithOneStep();
        JsonObject corr = new JsonObject();
        corr.addProperty("justification", "artefact");
        after.manualDetectionCorrections.add(corr);
        assertNotEquals(work(stateWithOneStep()), work(after));
    }
}
