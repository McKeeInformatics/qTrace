package io.qtrace;

import org.junit.jupiter.api.Test;

import static io.qtrace.QTraceAboutDialog.Offer.ACCOUNT;
import static io.qtrace.QTraceAboutDialog.Offer.COMPLIANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the About footer offers a workstation that runs Core alone. */
class QTraceAboutDialogTest {

    @Test
    void aStandardWorkstationIsOfferedAnAccount() {
        assertEquals(ACCOUNT, QTraceAboutDialog.offer(false, false));
    }

    @Test
    void aSignedInAccountIsOfferedCompliance() {
        assertEquals(COMPLIANCE, QTraceAboutDialog.offer(false, true));
    }

    @Test
    void aConfiguredCertificateIsNeverOfferedAnAccount() {
        assertEquals(COMPLIANCE, QTraceAboutDialog.offer(true, false));
        assertEquals(COMPLIANCE, QTraceAboutDialog.offer(true, true));
    }
}
