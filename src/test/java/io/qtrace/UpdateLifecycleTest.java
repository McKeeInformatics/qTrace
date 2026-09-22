package io.qtrace;

import io.qtrace.fixture.ProbeA;
import io.qtrace.fixture.ProbeB;
import io.qtrace.fixture.Sleeper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replays an auto-update on the real file system of the OS running the test —
 * run on Windows / macOS / Linux by .github/workflows/update-crossos.yml.
 *
 * Session 1: QuPath runs with v1 loaded (on Windows the JAR is now locked), the
 * updater writes v2 and reaps v1. QuPath quits. Session 2: QuPath starts again and
 * builds its extension classloader like QuPath 0.7 does (walk the extensions dir,
 * add EVERY JAR in file-system order — same-named JARs only trigger a warning, the
 * first one added wins), then qTrace's startup reap runs. The version actually
 * loaded must be v2, and only v2 may remain on disk. When the old JAR could not be
 * deleted (Windows lock), the cleanup helper scheduled in session 1 must have removed
 * it once the QuPath process exited, before session 2 starts.
 */
class UpdateLifecycleTest {

    @TempDir Path extensions;

    @Test
    void complianceUpdate_newVersionIsLoadedAfterRestart_andOldJarIsGone() throws Exception {
        assertUpdateLifecycle("compliance", ProbeB.class);
    }

    @Test
    void coreSelfUpdate_newVersionIsLoadedAfterRestart_andOldJarIsGone() throws Exception {
        assertUpdateLifecycle("core", ProbeA.class);
    }

    private void assertUpdateLifecycle(String module, Class<?> probe) throws Exception {
        jar(extensions.resolve("qtrace-" + module + "-1.1.5.jar"), probe, "1.1.5");
        byte[] v2 = Files.readAllBytes(jar(Files.createTempFile("v2", ".jar"), probe, "1.1.6"));

        // ── Session 1: v1 loaded, the user clicks Install ───────────────────────
        // A separate process stands in for the QuPath JVM: the cleanup helper must wait
        // for it to exit (the lock itself is held by this test's classloader, released
        // just before the "QuPath" process ends — as when a real JVM exits).
        Process qupath = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), Sleeper.class.getName()).start();
        Process cleanup = null;
        try (URLClassLoader session1 = qupathExtensionLoader()) {
            assertEquals("1.1.5", JarVersion.of(Class.forName(probe.getName(), true, session1)));
            JarInstaller.install(extensions, module, "1.1.6", v2);
            JarInstaller.ReapResult r = JarInstaller.reap(extensions, module);
            System.out.printf("[%s] session 1 reap: kept=%s deleted=%s failed=%s%n",
                System.getProperty("os.name"), r.kept(), r.deleted(), r.failed());
            if (!r.failed().isEmpty())
                cleanup = JarInstaller.scheduleDeleteAfterExit(qupath.pid(), r.failed().keySet());
        } // QuPath quits: locks released, then the process ends
        qupath.destroy();
        qupath.waitFor(30, TimeUnit.SECONDS);
        if (cleanup != null) {
            boolean done = cleanup.waitFor(60, TimeUnit.SECONDS);
            System.out.printf("[%s] cleanup helper finished=%s exit=%s%n",
                System.getProperty("os.name"), done, done ? cleanup.exitValue() : "-");
        }

        // ── Session 2: restart ──────────────────────────────────────────────────
        String loaded;
        try (URLClassLoader session2 = qupathExtensionLoader()) {
            loaded = JarVersion.of(Class.forName(probe.getName(), true, session2));
            JarInstaller.ReapResult r = JarInstaller.reap(extensions, module); // QTraceExtension startup reap
            System.out.printf("[%s] session 2 loaded %s, startup reap: deleted=%s failed=%s%n",
                System.getProperty("os.name"), loaded, r.deleted(), r.failed());
        }

        assertEquals("1.1.6", loaded, "version actually loaded after the restart");
        assertEquals(List.of("qtrace-" + module + "-1.1.6.jar"), jarNames(),
            "JARs left in extensions/ after the restart");
    }

    /** QuPath 0.7: walkFileTree over extensions/, every *.jar added in visit order. */
    private URLClassLoader qupathExtensionLoader() throws IOException {
        List<URL> urls = new ArrayList<>();
        Files.walkFileTree(extensions, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".jar")) urls.add(file.toUri().toURL());
                return FileVisitResult.CONTINUE;
            }
        });
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private List<String> jarNames() throws IOException {
        try (var s = Files.list(extensions)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jar")).sorted().toList();
        }
    }

    private static Path jar(Path target, Class<?> c, String version) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        String entry = c.getName().replace('.', '/') + ".class";
        try (OutputStream os = Files.newOutputStream(target);
             JarOutputStream jos = new JarOutputStream(os, mf);
             InputStream in = c.getClassLoader().getResourceAsStream(entry)) {
            jos.putNextEntry(new JarEntry(entry));
            in.transferTo(jos);
            jos.closeEntry();
        }
        return target;
    }
}
