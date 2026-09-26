package io.qtrace.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LicenseInstallerTest {

    @TempDir Path home;

    @Test
    void writesTheEnvelopeAsAQtlicenseFile() throws Exception {
        String env = "{\"jwt\":\"a.b.c\",\"sk_enc\":\"E\",\"sk_salt\":\"S\",\"sk_iv\":\"I\"}";
        Path p = LicenseInstaller.write(env, home);
        assertEquals(home.resolve("qtrace.qtlicense"), p);
        assertTrue(Files.readString(p).contains("\"jwt\": \"a.b.c\""), "pretty-printed like the portal download");
    }

    @Test
    void refusesAnEnvelopeWithoutJwt() {
        assertThrows(IllegalArgumentException.class, () -> LicenseInstaller.write("{\"sk_enc\":\"E\"}", home));
        assertThrows(IllegalArgumentException.class, () -> LicenseInstaller.write("not json", home));
    }

    @Test
    void keepsAnExistingLicenseFileAsBackup() throws Exception {
        Files.writeString(home.resolve("qtrace.qtlicense"), "old");
        LicenseInstaller.write("{\"jwt\":\"new\"}", home);
        assertEquals("old", Files.readString(home.resolve("qtrace.qtlicense.bak")));
    }
}
