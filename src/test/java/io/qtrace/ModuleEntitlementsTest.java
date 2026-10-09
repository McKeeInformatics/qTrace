package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModuleEntitlementsTest {

    private static final String DESCRIPTOR = """
        {"version":"1.2.4","files":[
          {"name":"qtrace-compliance-1.2.4.qtjar","module":"compliance","version":"1.2.4","url":"u","sha256":"a"},
          {"name":"qtrace-security-0.1.0.qtjar","module":"security","version":"0.1.0","url":"u","sha256":"b"}]}""";

    @Test
    void theServedModulesAreTheOnesOfTheDescriptor() {
        assertEquals(Set.of("compliance", "security"), ModuleEntitlements.names(DESCRIPTOR));
    }

    @Test
    void anEmptyDescriptorMeansNothingIsServed() {
        assertEquals(Set.of(), ModuleEntitlements.names("{\"version\":\"0\",\"files\":[]}"));
    }

    @Test
    void anUnreadableAnswerIsNotAnAnswer() {
        assertNull(ModuleEntitlements.names("<html>502</html>"));
        assertNull(ModuleEntitlements.names("{\"error\":\"Invalid or expired license\"}"));
        assertNull(ModuleEntitlements.names(null));
    }

    @Test
    void aServedModuleIsEntitled() {
        assertTrue(ModuleEntitlements.entitled("security", Set.of("compliance", "security"), Set.of()));
    }

    @Test
    void aModuleTheServerNoLongerServesIsNot() {
        assertFalse(ModuleEntitlements.entitled("security", Set.of("compliance"), Set.of()));
        assertFalse(ModuleEntitlements.entitled("security", Set.of(), Set.of()));
    }

    @Test
    void withoutAnyAnswerYetNothingIsTakenAway() {
        assertTrue(ModuleEntitlements.entitled("security", null, Set.of()));
    }

    @Test
    void thePublicModulesAreNeverTakenAway() {
        for (String m : new String[] {"core", "provisioning", "loader"})
            assertTrue(ModuleEntitlements.entitled(m, Set.of(), Set.of()));
    }

    @Test
    void aDeveloperOverrideKeepsAModuleOn() {
        assertTrue(ModuleEntitlements.entitled("security", Set.of(), Set.of("security")));
    }

    @Test
    void theLastAnswerIsKeptForTheNextOfflineStart(@TempDir Path dir) {
        Path f = dir.resolve("modules-entitled.json");
        ModuleEntitlements.write(f, Set.of("compliance", "security"));
        assertEquals(Set.of("compliance", "security"), ModuleEntitlements.read(f));
    }

    @Test
    void noFileOrABrokenOneIsNoAnswer(@TempDir Path dir) throws Exception {
        assertNull(ModuleEntitlements.read(dir.resolve("absent.json")));
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{not json");
        assertNull(ModuleEntitlements.read(broken));
    }

    @Test
    void theOverrideFileListsOneModulePerLine(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("dev-modules");
        Files.writeString(f, "security\n# a comment\n\n  standardbio  \n");
        assertEquals(Set.of("security", "standardbio"), ModuleEntitlements.readOverride(f));
        assertEquals(Set.of(), ModuleEntitlements.readOverride(dir.resolve("absent")));
    }

    @Test
    void whatIsServedIsWhatIsOpenToAnyonePlusWhatTheLicenceIncludes() {
        assertEquals(Set.of("player", "compliance"),
            ModuleEntitlements.combine(Set.of("player"), Set.of("compliance"), true));
    }

    @Test
    void withoutALicenceOnlyTheOpenModulesAreServed() {
        assertEquals(Set.of("player"), ModuleEntitlements.combine(Set.of("player"), null, false));
    }

    @Test
    void anAnswerThatDidNotComeLeavesTheLastOneStanding() {
        // The open list did not come, or the licence's did while there is a licence: unknown.
        assertNull(ModuleEntitlements.combine(null, Set.of("compliance"), true));
        assertNull(ModuleEntitlements.combine(Set.of("player"), null, true));
    }

    @Test
    void aModuleNoLongerDeclaredOpenIsSwitchedOff() {
        assertFalse(ModuleEntitlements.entitled("player", Set.of("compliance"), Set.of()));
        assertTrue(ModuleEntitlements.entitled("player", Set.of("compliance", "player"), Set.of()));
    }
}
