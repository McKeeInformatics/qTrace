package io.qtrace.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OnboardingStateTest {

    @TempDir Path home;

    @Test
    void showsTheTrunkOnAFreshInstall() {
        assertTrue(OnboardingState.load(home).shouldShowTrunk(""));
    }

    @Test
    void staysQuietOnceALicenseIsConfigured() {
        assertFalse(OnboardingState.load(home).shouldShowTrunk("/home/x/.qTrace/qtrace.qtlicense"));
    }

    @Test
    void staysQuietOnceDoneAndRemembersIt() {
        OnboardingState s = OnboardingState.load(home);
        s.markTrunkDone();
        assertFalse(OnboardingState.load(home).shouldShowTrunk(""));
        assertTrue(Files.isRegularFile(home.resolve("onboarding.json")));
    }

    @Test
    void aCorruptFileMeansAFreshStart() throws Exception {
        Files.writeString(home.resolve("onboarding.json"), "{not json");
        assertTrue(OnboardingState.load(home).shouldShowTrunk(null));
    }

    @Test
    void keepsFieldsItDoesNotKnow() throws Exception {
        Files.writeString(home.resolve("onboarding.json"), "{\"welcomeSeen\":true}");
        OnboardingState.load(home).markTrunkDone();
        String json = Files.readString(home.resolve("onboarding.json"));
        assertTrue(json.contains("welcomeSeen"), "keys written by a newer version survive");
    }
}
