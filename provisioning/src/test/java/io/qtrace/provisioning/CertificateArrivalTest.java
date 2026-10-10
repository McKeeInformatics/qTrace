package io.qtrace.provisioning;

import io.qtrace.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CertificateArrivalTest {

    @TempDir Path home;

    private static final String READY = """
        {"status":"ready","license":{"jwt":"a.b.c","sk_enc":"e","sk_salt":"s","sk_iv":"i"}}""";

    // ── The decision: the portal's answer → install, wait or ignore ──────────────

    @Test
    void aReadyAnswerInstallsTheEnvelopeItCarries() {
        CertificateArrival.Decision d = CertificateArrival.decide(READY);
        CertificateArrival.Install install = assertInstanceOf(CertificateArrival.Install.class, d);
        assertTrue(install.envelopeJson().contains("\"jwt\":\"a.b.c\""));
        assertTrue(install.envelopeJson().contains("\"sk_enc\":\"e\""));
    }

    @Test
    void aReadyAnswerWithoutAJwtIsIgnored() {
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide("{\"status\":\"ready\",\"license\":{\"sk_enc\":\"e\"}}"));
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide("{\"status\":\"ready\"}"));
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide("{\"status\":\"ready\",\"license\":{\"jwt\":\" \"}}"));
    }

    @Test
    void aNoneAnswerWaitsAndNamesTheMissingStep() {
        assertEquals(new CertificateArrival.Wait("identity"), CertificateArrival.decide("{\"status\":\"none\",\"step\":\"identity\"}"));
        assertEquals(new CertificateArrival.Wait("passphrase"), CertificateArrival.decide("{\"status\":\"none\",\"step\":\"passphrase\"}"));
        assertEquals(new CertificateArrival.Wait("unknown"), CertificateArrival.decide("{\"status\":\"none\"}"));
    }

    @Test
    void anythingElseIsIgnored() {
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide(null));
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide(""));
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide("<html>502</html>"));
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide("[]"));
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide("{\"status\":\"surprise\"}"));
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide("{\"error\":\"Invalid or expired token\"}"));
    }

    // ── The effect: files, in a folder that is not ~/.qTrace ─────────────────────

    @Test
    void installingWritesTheLicenceAndRemovesTheAccountToken() throws Exception {
        AccountInstaller.write("old.token.x", home);
        AccountInstaller.write("old.token.y", home);   // leaves a .bak beside the file
        assertTrue(Files.exists(home.resolve(Account.FILE + ".bak")));

        Path licence = CertificateArrival.writeCertificate(
            ((CertificateArrival.Install) CertificateArrival.decide(READY)).envelopeJson(), home);

        assertEquals(home.resolve(LicenseInstaller.FILE), licence);
        assertTrue(Files.readString(licence).contains("a.b.c"));
        assertFalse(Files.exists(home.resolve(Account.FILE)), "a workstation has a certificate or an account, not both");
        assertFalse(Files.exists(home.resolve(Account.FILE + ".bak")));
        assertNull(Account.jwt(home));
    }

    @Test
    void installingWithoutAnAccountFileIsFine() throws Exception {
        Path licence = CertificateArrival.writeCertificate(
            ((CertificateArrival.Install) CertificateArrival.decide(READY)).envelopeJson(), home);
        assertTrue(Files.isRegularFile(licence));
    }

    // ── When to ask ───────────────────────────────────────────────────────────────

    @Test
    void itAsksOnlyAWorkstationConnectedToAnAccountWithoutLicence() {
        assertTrue(CertificateArrival.shouldAsk(false, true));
        assertFalse(CertificateArrival.shouldAsk(true, true), "a licence already stands for this workstation");
        assertFalse(CertificateArrival.shouldAsk(false, false), "no account to ask for");
        assertFalse(CertificateArrival.shouldAsk(true, false));
    }
}
