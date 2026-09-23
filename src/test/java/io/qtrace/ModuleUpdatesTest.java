package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core's updater when it runs under the qTrace loader (docs/architecture/loader.md § 7):
 * offers come from the same descriptors as the loader (release bootstrap + per-license
 * /api/modules) and are compared with what is already in extensions/qtrace/ — including
 * a version downloaded but not loaded yet, which must not be offered again.
 */
class ModuleUpdatesTest {

    @TempDir Path dir;

    private Path qtjar(String name, String module, String version) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(new Attributes.Name("Qtrace-Module"), module);
        mf.getMainAttributes().put(new Attributes.Name("Qtrace-Version"), version);
        Path p = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(p); JarOutputStream j = new JarOutputStream(os, mf)) { }
        return p;
    }

    @Test
    void parsesDescriptor() {
        String json = "{\"version\":\"1.2.1\",\"files\":[{\"name\":\"qtrace-core-1.2.1.qtjar\",\"module\":\"core\","
            + "\"version\":\"1.2.1\",\"url\":\"https://x/core.qtjar\",\"sha256\":\"ab\"}]}";
        assertEquals(List.of(new ModuleUpdates.Offer("core", "1.2.1", "qtrace-core-1.2.1.qtjar",
            "https://x/core.qtjar", "ab", false)), ModuleUpdates.parse(json, false));
    }

    @Test
    void localVersions_highestPerModule_fromQtjarManifests() throws Exception {
        qtjar("qtrace-core-1.2.0.qtjar", "core", "1.2.0");
        qtjar("qtrace-core-1.2.1.qtjar", "core", "1.2.1");
        qtjar("qtrace-compliance-1.2.0.qtjar", "compliance", "1.2.0");
        Files.writeString(dir.resolve("index.json"), "{}");
        assertEquals(Map.of("core", "1.2.1", "compliance", "1.2.0"), ModuleUpdates.localVersions(dir));
    }

    @Test
    void pending_onlyNewerThanWhatIsOnDisk() {
        var remote = List.of(
            new ModuleUpdates.Offer("core", "1.2.1", "c", "u", "s", false),
            new ModuleUpdates.Offer("compliance", "1.2.0", "p", "u", "s", true),
            new ModuleUpdates.Offer("acme", "1.0.0", "a", "u", "s", true));
        var local = Map.of("core", "1.2.1", "compliance", "1.1.9");
        assertEquals(List.of("compliance", "acme"),
            ModuleUpdates.pending(remote, local).stream().map(ModuleUpdates.Offer::module).toList());
    }

    @Test
    void loaderMode_isDetectedFromTheCodeSource() {
        assertTrue(ModuleUpdates.isLoaderMode(Path.of("/x/extensions/qtrace/qtrace-core-1.2.0.qtjar")));
        assertFalse(ModuleUpdates.isLoaderMode(Path.of("/x/extensions/qtrace-core-1.1.5.jar")));
        assertFalse(ModuleUpdates.isLoaderMode(Path.of("/x/build/classes/java/main")));
    }
}
