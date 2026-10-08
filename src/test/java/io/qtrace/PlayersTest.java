package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlayersTest {

    /** Records what it was asked to open: "" for the empty player, the source otherwise. */
    private static final class Recording implements Player {
        final List<String> opened = new ArrayList<>();
        @Override public void open() { opened.add(""); }
        @Override public void open(String source) { opened.add(source); }
    }

    @AfterEach
    void clear() {
        Players.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void noPlayerWithoutAModule() {
        ModuleEntitlements.setForTest(Set.of("player"));
        assertNull(Players.entitled());
        assertFalse(Players.open());
        assertFalse(Players.open("qtc_abc"));
    }

    @Test
    void theRegisteredPlayerOpensEmptyOrOnASource() {
        ModuleEntitlements.setForTest(Set.of("player"));
        Recording player = new Recording();
        Players.register("player", player);
        assertSame(player, Players.entitled());
        assertTrue(Players.open());
        assertTrue(Players.open("qtc_abc"));
        assertEquals(List.of("", "qtc_abc"), player.opened);
    }

    @Test
    void thePlayerIsOfferedWhileItsModuleIsServed() {
        Players.register("player", new Recording());
        ModuleEntitlements.setForTest(Set.of("compliance", "player"));
        assertNotNull(Players.entitled());
        // No longer declared open in the BackOffice, nor included in the licence: switched off.
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertNull(Players.entitled());
    }

    @Test
    void aPlayerFromAModuleTheLicenceDoesNotIncludeIsNotOffered() {
        Recording player = new Recording();
        Players.register("other", player);
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertNull(Players.entitled());
        assertFalse(Players.open("qtc_abc"));
        assertTrue(player.opened.isEmpty());
    }

    @Test
    void aModuleRegisteringTwiceKeepsItsLastPlayer() {
        ModuleEntitlements.setForTest(Set.of("player"));
        Recording first = new Recording(), again = new Recording();
        Players.register("player", first);
        Players.register("player", again);
        assertSame(again, Players.entitled());
    }

    @Test
    void nullsAreIgnored() {
        ModuleEntitlements.setForTest(Set.of("player"));
        Players.register(null, new Recording());
        Players.register("player", null);
        assertNull(Players.entitled());
    }

    @Test
    void listenersAreToldWhenAPlayerRegisters() {
        ModuleEntitlements.setForTest(Set.of("player"));
        int[] told = {0};
        Players.onChange(this, () -> told[0]++);
        Players.register("player", new Recording());
        assertEquals(1, told[0]);
    }
}
