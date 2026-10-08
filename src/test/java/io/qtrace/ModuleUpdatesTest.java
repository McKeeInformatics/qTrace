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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void folderOfCodeSource_acceptsQtjar_soUpdatesLandInExtensionsQtrace() throws Exception {
        Path qtjar = qtjar("qtrace-core-1.2.0.qtjar", "core", "1.2.0");
        Path jar = Files.writeString(dir.resolve("qtrace-core-1.1.5.jar"), "x");
        assertEquals(dir, ModuleUpdates.folderOf(qtjar));
        assertEquals(dir, ModuleUpdates.folderOf(jar));
        assertEquals(dir, ModuleUpdates.folderOf(dir)); // classes directory (dev)
        assertEquals(null, ModuleUpdates.folderOf(dir.resolve("missing.qtjar")));
    }

    @Test
    void loaderMode_isDetectedFromTheCodeSource() {
        assertTrue(ModuleUpdates.isLoaderMode(Path.of("/x/extensions/qtrace/qtrace-core-1.2.0.qtjar")));
        assertFalse(ModuleUpdates.isLoaderMode(Path.of("/x/extensions/qtrace-core-1.1.5.jar")));
        assertFalse(ModuleUpdates.isLoaderMode(Path.of("/x/build/classes/java/main")));
    }

    private static final String CARDS = """
        {"version":"1.1.0","files":[
          {"name":"qtrace-player-1.1.0.qtjar","module":"player","version":"1.1.0",
           "url":"https://www.qtrace.ca/api/modules/player/download","sha256":"ab",
           "title":"qTrace Player","tagline":"Replay a recorded analysis.","icon":"player",
           "features":["Step by step","In batch","Pre-flight check"],
           "whatsNew":["Sessions as circles","Scripts shown"],
           "welcome":{"revision":3,"slides":[
             {"title":"Open a record","text":"Paste a qtc_… ID or pick a file.","image":"/docs/bricks/screenshots/player.png"},
             {"title":"Pick your images","text":""},
             {"text":"no title: skipped"}]}},
          {"name":"qtrace-plain-0.2.0.qtjar","module":"plain","version":"0.2.0",
           "url":"https://www.qtrace.ca/api/modules/plain/download","sha256":"cd"}]}""";

    @Test
    void cards_carryWhatToShowAboutEachModule() {
        List<ModuleUpdates.Card> cards = ModuleUpdates.parseCards(CARDS, false);
        assertEquals(2, cards.size());
        ModuleUpdates.Card player = cards.get(0);
        assertEquals("player", player.offer().module());
        assertFalse(player.offer().licensed(), "an open module downloads without a licence");
        assertEquals("qTrace Player", player.title());
        assertEquals("Replay a recorded analysis.", player.tagline());
        assertEquals("player", player.icon());
        assertEquals(List.of("Step by step", "In batch", "Pre-flight check"), player.features());
        assertEquals(List.of("Sessions as circles", "Scripts shown"), player.whatsNew());
        // Its welcome: the slides after the first one, and the revision that makes it play again.
        assertEquals(3, player.welcome().revision());
        assertEquals(2, player.welcome().slides().size());
        assertEquals("Open a record", player.welcome().slides().get(0).title());
        assertEquals("/docs/bricks/screenshots/player.png", player.welcome().slides().get(0).image());
        assertNull(player.welcome().slides().get(1).image());
        assertTrue(player.hasWelcome());
    }

    @Test
    void cards_fromAServerThatSaysNothingMore_fallBackOnTheModuleName() {
        ModuleUpdates.Card plain = ModuleUpdates.parseCards(CARDS, true).get(1);
        assertTrue(plain.offer().licensed());
        assertEquals("plain", plain.title());
        assertEquals("", plain.tagline());
        assertEquals("plain", plain.icon());
        assertTrue(plain.features().isEmpty());
        assertTrue(plain.whatsNew().isEmpty());
        assertEquals(0, plain.welcome().revision());
        assertTrue(plain.welcome().slides().isEmpty());
        assertFalse(plain.hasWelcome(), "nothing was written about it: no welcome to play");
    }

    @Test
    void cards_fromAnAnswerThatIsNotADescriptor_areNone() {
        assertTrue(ModuleUpdates.parseCards("<html>404</html>", false).isEmpty());
        assertTrue(ModuleUpdates.parseCards("{\"error\":\"x\"}", false).isEmpty());
    }

    @Test
    void merge_keepsOneOfferPerModule_theFirstListWins() {
        ModuleUpdates.Offer open = new ModuleUpdates.Offer("player", "1.0.0", "p.qtjar", "u1", "ab", false);
        ModuleUpdates.Offer licensed = new ModuleUpdates.Offer("player", "1.0.0", "p.qtjar", "u2", "ab", true);
        ModuleUpdates.Offer other = new ModuleUpdates.Offer("cohort", "0.1.0", "c.qtjar", "u3", "cd", true);
        assertEquals(List.of(open, other), ModuleUpdates.merge(List.of(open), List.of(licensed, other)));
    }
}
