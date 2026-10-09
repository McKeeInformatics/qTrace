package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ProvisionersTest {

    /** A workstation still to be set up, until Getting started is done. */
    private static final class Fake implements Provisioner {
        boolean pending = true;
        @Override public boolean gettingStartedPending() { return pending; }
        @Override public void gettingStartedDone() { pending = false; }
        @Override public CompletableFuture<List<OpenModule>> provisionOpenModules() {
            return CompletableFuture.completedFuture(List.of(new OpenModule("player", "qTrace Player", true)));
        }
        @Override public void checkNetwork(BiConsumer<String, Boolean> reached) { reached.accept("qtrace.ca", true); }
        @Override public void signIn(SignIn listener, BooleanSupplier cancelled,
                                     boolean quitDialog, String invite) { listener.starting(); }
    }

    @AfterEach
    void clear() {
        Provisioners.clear();
    }

    @Test
    void nothingToSetUpWithoutTheModule() {
        assertNull(Provisioners.current());
        assertFalse(Provisioners.gettingStartedPending());
    }

    @Test
    void theRegisteredModuleSaysWhetherGettingStartedIsStillToBeShown() {
        Fake fake = new Fake();
        Provisioners.register(fake);
        assertSame(fake, Provisioners.current());
        assertTrue(Provisioners.gettingStartedPending());
        Provisioners.current().gettingStartedDone();
        assertFalse(Provisioners.gettingStartedPending());
    }

    @Test
    void registeringAgainReplacesTheModule() {
        Provisioners.register(new Fake());
        Fake second = new Fake();
        Provisioners.register(second);
        assertSame(second, Provisioners.current());
    }

    @Test
    void registeringNothingChangesNothing() {
        Fake fake = new Fake();
        Provisioners.register(fake);
        Provisioners.register(null);
        assertSame(fake, Provisioners.current());
    }
}
