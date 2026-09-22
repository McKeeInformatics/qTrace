package io.qtrace;

import io.qtrace.fixture.ProbeA;
import io.qtrace.fixture.ProbeB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Core and Compliance both put classes in package io.qtrace, and QuPath loads every
 * extension JAR into ONE classloader. A Package is defined once per classloader, from
 * the manifest of whichever JAR loaded a class of that package first — so
 * getPackage().getImplementationVersion() reports the SAME version for both modules.
 * After a Compliance-only update (Core 1.1.5 + Compliance 1.1.6) the plugin announced
 * 1.1.5 and the updater re-offered 1.1.6 forever. Each module's version must come
 * from its own JAR.
 */
class JarVersionTest {

    @TempDir Path tmp;

    @Test
    void jarVersion_readsEachClassOwnJar_evenWhenPackageIsSplitAcrossJars() throws Exception {
        Path core = jar(tmp.resolve("core.jar"), ProbeA.class, "1.1.5");
        Path comp = jar(tmp.resolve("compliance.jar"), ProbeB.class, "1.1.6");

        // Parent = platform loader, so the fixtures come from the JARs, not the test classpath.
        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{ core.toUri().toURL(), comp.toUri().toURL() },
                ClassLoader.getPlatformClassLoader())) {
            Class<?> a = Class.forName(ProbeA.class.getName(), false, cl); // defines the package first
            Class<?> b = Class.forName(ProbeB.class.getName(), false, cl);

            assertEquals("1.1.5", JarVersion.of(a));
            assertEquals("1.1.6", JarVersion.of(b));
        }
    }

    @Test
    void jarVersion_isDev_whenRunFromClassesDirectory() {
        // Test classes are loaded from build/classes (no JAR, no manifest) — like an IDE run.
        assertEquals("dev", JarVersion.of(ProbeA.class));
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
