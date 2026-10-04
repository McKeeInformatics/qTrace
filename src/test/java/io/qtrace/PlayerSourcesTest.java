package io.qtrace;

import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSourcesTest {

    private static PlayerSource source(String label) {
        return new PlayerSource() {
            @Override public String label() { return label; }
            @Override public void choose(Window owner, Consumer<String> open) { open.accept("qtc_" + label); }
        };
    }

    @AfterEach
    void clear() {
        PlayerSources.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void noSourceWithoutAModule() {
        ModuleEntitlements.setForTest(Set.of("training"));
        assertTrue(PlayerSources.entitled().isEmpty());
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeOffersNothing() {
        PlayerSources.register("training", source("Training…"));
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertTrue(PlayerSources.entitled().isEmpty());
        ModuleEntitlements.setForTest(Set.of("compliance", "training"));
        assertEquals(1, PlayerSources.entitled().size());
    }

    @Test
    void sourcesComeInRegistrationOrder() {
        ModuleEntitlements.setForTest(Set.of("training", "other"));
        PlayerSource a = source("A"), b = source("B");
        PlayerSources.register("training", a);
        PlayerSources.register("other", b);
        assertEquals(List.of(a, b), PlayerSources.entitled());
    }

    @Test
    void aModuleRegisteringTwiceIsListedOnce() {
        ModuleEntitlements.setForTest(Set.of("training"));
        PlayerSource first = source("A"), again = source("A");
        PlayerSources.register("training", first);
        PlayerSources.register("training", again);
        assertEquals(List.of(again), PlayerSources.entitled());
    }

    @Test
    void nullsAreIgnored() {
        ModuleEntitlements.setForTest(Set.of("training"));
        PlayerSources.register(null, source("A"));
        PlayerSources.register("training", null);
        assertTrue(PlayerSources.entitled().isEmpty());
    }
}
