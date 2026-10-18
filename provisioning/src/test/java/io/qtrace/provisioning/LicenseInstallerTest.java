package io.qtrace.provisioning;

import org.junit.jupiter.api.Test;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.condition.DisabledOnOs;
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

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theLicenseAndItsBackupAreReadableByTheirOwnerOnly() throws Exception {
        Path dir = home.resolve(".qTrace");
        LicenseInstaller.write("{\"jwt\":\"old\"}", dir);
        LicenseInstaller.write("{\"jwt\":\"new\"}", dir);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("qtrace.qtlicense"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("qtrace.qtlicense.bak"))));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }

    @Test
    void leavesNoTemporaryFileBehind() throws Exception {
        LicenseInstaller.write("{\"jwt\":\"a\"}", home);
        try (var names = Files.list(home)) {
            assertTrue(names.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")));
        }
    }
}
