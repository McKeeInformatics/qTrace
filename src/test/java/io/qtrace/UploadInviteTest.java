package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.qtrace.UploadInvite.State.*;
import static org.junit.jupiter.api.Assertions.*;

class UploadInviteTest {

    @AfterEach
    void clear() {
        UploadInvite.clear();
    }

    @Test
    void uploadIsThereWithAnActiveLicenceAndThePushModule() {
        assertEquals(UPLOAD, UploadInvite.state(true, true, true, true));
        assertEquals(UPLOAD, UploadInvite.state(true, true, true, false));
    }

    @Test
    void aWorkstationWithoutAnAccountIsInvitedToSignIn() {
        assertEquals(SIGN_IN, UploadInvite.state(false, false, false, true));
        // The upload module may be there already (a developer's build): still no account, still the invitation.
        assertEquals(SIGN_IN, UploadInvite.state(false, true, false, true));
    }

    @Test
    void noInvitationWithoutAWindowToSignInFrom() {
        assertEquals(NONE, UploadInvite.state(false, false, false, false));
    }

    @Test
    void aCertificateThatDoesNotGiveUploadIsNotInvitedToCreateAnAccount() {
        assertEquals(NONE, UploadInvite.state(false, true, true, true), "inactive or expired licence");
        assertEquals(NONE, UploadInvite.state(true, false, true, true), "licence without the upload module");
    }

    @Test
    void aSignedInAccountSeesUploadWhenTheModuleIsServed() {
        // "signed in" = active licence or basic account; the rule does not tell them apart.
        assertEquals(UPLOAD, UploadInvite.state(true, true, true, true));
        assertEquals(UPLOAD, UploadInvite.state(true, true, true, false));
    }

    @Test
    void aSignedInAccountWithoutTheUploadModuleIsNeitherInvitedNorOffered() {
        assertEquals(NONE, UploadInvite.state(true, false, true, true));
    }

    @Test
    void anExpiredAccountWithNoLicenceIsInvitedToSignInAgain() {
        // hasIdentity is false once the token expired (Account.signedIn), so the invitation comes back.
        assertEquals(SIGN_IN, UploadInvite.state(false, true, false, true));
    }

    @Test
    void theInvitationOpensTheWindowTheWelcomeModuleRegistered() {
        assertFalse(UploadInvite.available());
        assertFalse(UploadInvite.open());
        AtomicInteger opened = new AtomicInteger();
        UploadInvite.register(opened::incrementAndGet);
        assertTrue(UploadInvite.available());
        assertTrue(UploadInvite.open());
        assertEquals(1, opened.get());
    }

    @Test
    void thePanelsAreToldWhenTheWindowBecomesAvailable() {
        AtomicInteger redraws = new AtomicInteger();
        UploadInvite.onChange(this, redraws::incrementAndGet);
        UploadInvite.register(() -> {});
        assertEquals(1, redraws.get());
    }
}
