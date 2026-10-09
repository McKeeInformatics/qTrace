package io.qtrace.provisioning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProvisioningStateTest {

    @TempDir Path home;

    @Test
    void showsTheTrunkOnAFreshInstall() {
        assertTrue(ProvisioningState.load(home).shouldShowTrunk(""));
    }

    @Test
    void staysQuietOnceALicenseIsConfigured() {
        assertFalse(ProvisioningState.load(home).shouldShowTrunk("/home/x/.qTrace/qtrace.qtlicense"));
    }

    @Test
    void staysQuietOnceDoneAndRemembersIt() {
        ProvisioningState s = ProvisioningState.load(home);
        s.markTrunkDone();
        assertFalse(ProvisioningState.load(home).shouldShowTrunk(""));
        assertTrue(Files.isRegularFile(home.resolve("provisioning.json")));
    }

    @Test
    void aCorruptFileMeansAFreshStart() throws Exception {
        Files.writeString(home.resolve("provisioning.json"), "{not json");
        assertTrue(ProvisioningState.load(home).shouldShowTrunk(null));
    }

    @Test
    void keepsFieldsItDoesNotKnow() throws Exception {
        Files.writeString(home.resolve("provisioning.json"), "{\"welcomeSeen\":true}");
        ProvisioningState.load(home).markTrunkDone();
        String json = Files.readString(home.resolve("provisioning.json"));
        assertTrue(json.contains("welcomeSeen"), "keys written by a newer version survive");
    }
}
