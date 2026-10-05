package io.qtrace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplaySettingsRecordTest {

    private static DisplaySettingsRecord record(float max, boolean selected, double gamma, String at) {
        return new DisplaySettingsRecord(
            List.of(new DisplaySettingsRecord.ChannelSetting("DAPI", 0x0000FF, 0f, max, selected)),
            gamma, false, false, Instant.parse(at));
    }

    @Test
    void theSameDisplayReadLaterIsTheSameSettings() {
        assertTrue(record(255f, true, 1.0, "2026-10-05T19:34:10Z")
            .sameSettings(record(255f, true, 1.0, "2026-10-05T19:36:00Z")));
    }

    @Test
    void aChangedRangeChannelOrGammaIsNot() {
        DisplaySettingsRecord base = record(255f, true, 1.0, "2026-10-05T19:34:10Z");
        assertFalse(base.sameSettings(record(180f, true, 1.0, "2026-10-05T19:34:10Z")));
        assertFalse(base.sameSettings(record(255f, false, 1.0, "2026-10-05T19:34:10Z")));
        assertFalse(base.sameSettings(record(255f, true, 0.8, "2026-10-05T19:34:10Z")));
        assertFalse(base.sameSettings(null));
    }
}
