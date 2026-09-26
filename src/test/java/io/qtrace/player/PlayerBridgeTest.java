package io.qtrace.player;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The HTML player can only ask QuPath for the actions registered here (loader.md § 17). */
class PlayerBridgeTest {

    @Test
    void runsARegisteredActionWithItsArgument() {
        List<String> got = new ArrayList<>();
        PlayerBridge b = new PlayerBridge().on("open-url", got::add);
        assertTrue(b.dispatch("open-url", "https://www.qtrace.ca/docs"));
        assertEquals(List.of("https://www.qtrace.ca/docs"), got);
    }

    @Test
    void refusesAnythingElse() {
        List<String> got = new ArrayList<>();
        PlayerBridge b = new PlayerBridge().on("open-url", got::add);
        assertFalse(b.dispatch("exec", "rm -rf ~"));
        assertFalse(b.dispatch(null, "x"));
        assertTrue(got.isEmpty());
    }

    @Test
    void aNullArgumentBecomesEmpty() {
        List<String> got = new ArrayList<>();
        new PlayerBridge().on("continue", got::add).dispatch("continue", null);
        assertEquals(List.of(""), got);
    }

    @Test
    void aFailingHandlerDoesNotEscapeToThePage() {
        PlayerBridge b = new PlayerBridge().on("boom", a -> { throw new IllegalStateException("x"); });
        assertTrue(b.dispatch("boom", ""));
    }

    @Test
    void onlyThePlayerOriginGetsTheBridge() {
        assertTrue(PlayerBridge.trustedLocation("jar:file:/x/qtrace-onboarding-1.0.0.qtjar!/io/qtrace/onboarding/player/player.html",
            "jar:file:/x/qtrace-onboarding-1.0.0.qtjar!/io/qtrace/onboarding/player/player.html"));
        assertTrue(PlayerBridge.trustedLocation("https://www.qtrace.ca/player/player.html",
            "https://www.qtrace.ca/player/player.html#step"));
        assertFalse(PlayerBridge.trustedLocation("https://www.qtrace.ca/player/player.html",
            "https://evil.example/player/player.html"));
        assertFalse(PlayerBridge.trustedLocation("https://www.qtrace.ca/player/player.html",
            "https://www.qtrace.ca/player/player.html.evil.example/"));
        assertFalse(PlayerBridge.trustedLocation("https://www.qtrace.ca/player/player.html", null));
    }
}
