package io.qtrace;

import io.qtrace.fixture.Sleeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deferred cleanup that removes superseded JARs Windows would not let us delete
 * while QuPath runs: it must wait for the QuPath process to exit, then delete —
 * including paths with spaces and quotes (Windows user names, "(1)" downloads).
 */
class JarInstallerTest {

    @TempDir Path dir;

    @Test
    void scheduleDeleteAfterExit_waitsForTheProcess_thenDeletes() throws Exception {
        Path plain = Files.writeString(dir.resolve("qtrace-compliance-1.1.5.jar"), "old");
        Path odd = Files.writeString(dir.resolve("qtrace-compliance-1.1.4 (1) it's.jar"), "old");
        Process qupath = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), Sleeper.class.getName()).start();
        try {
            Process cleanup = JarInstaller.scheduleDeleteAfterExit(qupath.pid(), List.of(plain, odd));
            assertNotNull(cleanup);

            Thread.sleep(2_000);
            assertTrue(Files.exists(plain), "must not delete while QuPath is still running");

            qupath.destroy();
            assertTrue(cleanup.waitFor(60, TimeUnit.SECONDS), "cleanup helper did not finish");
            assertFalse(Files.exists(plain), "deleted after QuPath exited");
            assertFalse(Files.exists(odd), "path with spaces/quotes deleted too");
        } finally {
            qupath.destroyForcibly();
        }
    }
}
