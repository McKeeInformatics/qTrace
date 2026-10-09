package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class WorkspacePushesTest {

    private static final WorkspacePush SENT = request -> CompletableFuture.completedFuture("https://qtrace.ca/workspace/x");

    @AfterEach
    void clear() {
        WorkspacePushes.clear();
        ModuleEntitlements.setForTest(null);
    }

    @Test
    void noPushWithoutAModule() {
        ModuleEntitlements.setForTest(Set.of("upload"));
        assertNull(WorkspacePushes.entitled());
    }

    @Test
    void theRegisteredPushIsTheOneOffered() {
        ModuleEntitlements.setForTest(Set.of("upload"));
        WorkspacePushes.register("upload", SENT);
        assertSame(SENT, WorkspacePushes.entitled());
    }

    @Test
    void thePushIsOfferedWhileItsModuleIsServed() {
        WorkspacePushes.register("upload", SENT);
        ModuleEntitlements.setForTest(Set.of("compliance", "upload"));
        assertNotNull(WorkspacePushes.entitled());
        ModuleEntitlements.setForTest(Set.of("compliance"));
        assertNull(WorkspacePushes.entitled());
    }

    @Test
    void aModuleRegisteringTwiceKeepsItsLastPush() {
        ModuleEntitlements.setForTest(Set.of("upload"));
        WorkspacePush again = request -> CompletableFuture.completedFuture(null);
        WorkspacePushes.register("upload", SENT);
        WorkspacePushes.register("upload", again);
        assertSame(again, WorkspacePushes.entitled());
    }

    @Test
    void nullsAreIgnored() {
        ModuleEntitlements.setForTest(Set.of("upload"));
        WorkspacePushes.register(null, SENT);
        WorkspacePushes.register("upload", null);
        assertNull(WorkspacePushes.entitled());
    }

    @Test
    void listenersHearEachRegistration() {
        List<String> heard = new ArrayList<>();
        WorkspacePushes.onChange("panel", () -> heard.add("panel"));
        WorkspacePushes.onChange("panel", () -> heard.add("panel again")); // one listener per owner
        WorkspacePushes.register("upload", SENT);
        assertEquals(List.of("panel again"), heard);
    }
}
