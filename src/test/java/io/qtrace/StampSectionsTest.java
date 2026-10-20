package io.qtrace;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class StampSectionsTest {

    private static StampSection section(boolean ready) {
        return new StampSection() {
            @Override public Node node() { return null; }
            @Override public ObservableBooleanValue ready() { return new SimpleBooleanProperty(ready); }
        };
    }

    // Explicit, not whatever ~/.qTrace/modules-entitled.json says on the machine running the tests.
    @org.junit.jupiter.api.BeforeEach
    void securityIsIncluded() {
        ModuleEntitlements.setForTest(java.util.Set.of("security"));
    }

    @AfterEach
    void clear() {
        StampSections.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void aModuleTheLicenceNoLongerIncludesAddsNothing() {
        StampSections.register("security", () -> section(false));
        ModuleEntitlements.setForTest(java.util.Set.of("compliance"));
        assertTrue(StampSections.create().isEmpty());
        ModuleEntitlements.setForTest(java.util.Set.of("compliance", "security"));
        assertEquals(1, StampSections.create().size());
    }

    @Test
    void noSectionWithoutAModule() {
        assertTrue(StampSections.create().isEmpty());
    }

    @Test
    void aRegisteredSectionIsBuiltAnewForEachDialog() {
        int[] built = {0};
        StampSections.register("security", () -> { built[0]++; return section(true); });
        assertEquals(1, StampSections.create().size());
        assertEquals(1, StampSections.create().size());
        assertEquals(2, built[0]);
    }

    @Test
    void sectionsComeInRegistrationOrder() {
        StampSection a = section(true), b = section(false);
        StampSections.register("security", () -> a);
        StampSections.register("security", () -> b);
        assertEquals(List.of(a, b), StampSections.create());
    }

    @Test
    void aSectionThatFailsToBuildNeverBlocksTheStamp() {
        StampSection ok = section(true);
        Supplier<StampSection> broken = () -> { throw new IllegalStateException("boom"); };
        StampSections.register("security", broken);
        StampSections.register("security", () -> null);
        StampSections.register("security", () -> ok);
        assertEquals(List.of(ok), StampSections.create());
    }

    @Test
    void allReadyOnlyWhenEverySectionIs() {
        assertTrue(StampSections.allReady(List.of()));
        assertTrue(StampSections.allReady(List.of(section(true), section(true))));
        assertFalse(StampSections.allReady(List.of(section(true), section(false))));
    }

    private static StampSection checking(java.util.function.Function<StampContext, java.util.concurrent.CompletionStage<StampEvidence>> check) {
        return new StampSection() {
            @Override public Node node() { return null; }
            @Override public ObservableBooleanValue ready() { return new SimpleBooleanProperty(true); }
            @Override public java.util.concurrent.CompletionStage<StampEvidence> check(StampContext c) { return check.apply(c); }
        };
    }

    private static final StampContext CONTEXT = new StampContext("n0nce-n0nce-n0nce-n0nce", "slide.svs", "ab");

    @Test
    void checksJoinTheirProofsInOrderAndSkipNone() throws Exception {
        StampEvidence p1 = new StampEvidence("security", "second-factor", "jwt1");
        StampEvidence p3 = new StampEvidence("other", "kind", "v3");
        List<StampSection> sections = List.of(
            checking(c -> java.util.concurrent.CompletableFuture.completedFuture(p1)),
            section(true),                                                // default: checks nothing
            checking(c -> java.util.concurrent.CompletableFuture.completedFuture(p3)));
        assertEquals(List.of(p1, p3), StampSections.check(sections, CONTEXT).get());
        assertEquals(List.of(), StampSections.check(List.of(), CONTEXT).get());
    }

    @Test
    void everyCheckSeesTheSameContext() throws Exception {
        List<StampContext> seen = new java.util.ArrayList<>();
        StampSection s = checking(c -> { seen.add(c); return java.util.concurrent.CompletableFuture.completedFuture(null); });
        StampSections.check(List.of(s, s), CONTEXT).get();
        assertEquals(List.of(CONTEXT, CONTEXT), seen);
    }

    @Test
    void aRefusalStopsTheStampAndTheChecksAfterIt() {
        boolean[] reached = {false};
        List<StampSection> sections = List.of(
            checking(c -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("wrong code"))),
            checking(c -> { reached[0] = true; return java.util.concurrent.CompletableFuture.completedFuture(null); }));
        var all = StampSections.check(sections, CONTEXT);
        assertThrows(java.util.concurrent.ExecutionException.class, all::get);
        assertFalse(reached[0]);
    }

    @Test
    void aCheckThatThrowsRefusesTheStamp() {
        StampSection broken = checking(c -> { throw new IllegalStateException("bug"); });
        assertThrows(java.util.concurrent.ExecutionException.class, () -> StampSections.check(List.of(broken), CONTEXT).get());
    }

    @Test
    void aNonceIsFreshAndUrlSafe() {
        String a = StampContext.newNonce(), b = StampContext.newNonce();
        assertNotEquals(a, b);
        assertTrue(a.matches("[A-Za-z0-9_-]{32}"), a);
    }
}
