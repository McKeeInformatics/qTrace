package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

class SecretFilesTest {

    @TempDir Path tmp;

    private static String mode(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    @Test
    void writesTheContentAndLeavesNoTemporaryFile() throws Exception {
        Path f = tmp.resolve("a").resolve("secret");
        SecretFiles.write(f, "one");
        SecretFiles.write(f, "two");
        assertEquals("two", Files.readString(f));
        try (var names = Files.list(f.getParent())) {
            assertEquals(1, names.count());
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void createsFileAndFolderPrivate() throws Exception {
        Path f = tmp.resolve(".qTrace").resolve("secret");
        SecretFiles.write(f, "x");
        assertEquals("rw-------", mode(f));
        assertEquals("rwx------", mode(f.getParent()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void replacingAnOpenFileMakesItPrivate() throws Exception {
        Path f = tmp.resolve("secret");
        Files.writeString(f, "old");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        SecretFiles.write(f, "new");
        assertEquals("rw-------", mode(f));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void copyIsPrivateToo() throws Exception {
        Path a = tmp.resolve("a");
        Files.writeString(a, "s");
        Path b = tmp.resolve("b");
        SecretFiles.copy(a, b);
        assertEquals("s", Files.readString(b));
        assertEquals("rw-------", mode(b));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void tightenAllFixesOldFilesAndOnlyThem() throws Exception {
        Path dir = tmp.resolve(".qTrace");
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        for (String n : new String[] {"qtrace.qtlicense", "qtrace.qtlicense.bak", "qtrace.qtaccount", "qtrace-signing.key", "other.json"}) {
            Path f = dir.resolve(n);
            Files.writeString(f, "x");
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        }
        SecretFiles.tightenAll(dir);
        assertEquals("rwx------", mode(dir));
        assertEquals("rw-------", mode(dir.resolve("qtrace.qtlicense")));
        assertEquals("rw-------", mode(dir.resolve("qtrace.qtlicense.bak")));
        assertEquals("rw-------", mode(dir.resolve("qtrace.qtaccount")));
        assertEquals("rw-------", mode(dir.resolve("qtrace-signing.key")));
        assertEquals("rw-r--r--", mode(dir.resolve("other.json")));
    }

    @Test
    void restrictReportsOnlyAChange() throws Exception {
        Path f = tmp.resolve("s");
        SecretFiles.write(f, "x");
        assertFalse(SecretFiles.restrict(f), "already private");
        assertFalse(SecretFiles.restrict(tmp.resolve("missing")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void restrictReportsAnOpenFile() throws Exception {
        Path f = tmp.resolve("s");
        Files.writeString(f, "x");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-rw-r--"));
        assertTrue(SecretFiles.restrict(f));
    }
}
