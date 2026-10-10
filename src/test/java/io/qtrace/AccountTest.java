package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** The account token a basic account holds in ~/.qTrace/qtrace.qtaccount — read for display and presented to the server. */
class AccountTest {

    @TempDir Path dir;

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private static String jwt(String payloadJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    private void store(String jwt) throws Exception {
        Files.writeString(dir.resolve(Account.FILE), "{\"jwt\":\"" + jwt + "\"}");
    }

    @Test
    void noFileMeansNoAccount() {
        assertNull(Account.jwt(dir));
        assertNull(Account.info(dir));
        assertFalse(Account.signedIn(dir, NOW));
    }

    @Test
    void readsTheTokenFromTheEnvelope() throws Exception {
        String t = jwt("{\"plan\":\"account\",\"name\":\"Ada\",\"exp\":1900000000}");
        store(t);
        assertEquals(t, Account.jwt(dir));
    }

    @Test
    void readsWhatToShowFromThePayload() throws Exception {
        store(jwt("{\"plan\":\"account\",\"name\":\"Ada Lovelace\",\"email\":\"ada@lab.org\","
            + "\"institution\":\"Analytical Lab\",\"sub\":\"user_1\",\"exp\":1900000000}"));
        Account.Info i = Account.info(dir);
        assertEquals("Ada Lovelace", i.name());
        assertEquals("ada@lab.org", i.email());
        assertEquals("Analytical Lab", i.institution());
    }

    @Test
    void missingFieldsAreEmptyNotNull() throws Exception {
        store(jwt("{\"plan\":\"account\",\"exp\":1900000000}"));
        Account.Info i = Account.info(dir);
        assertEquals("", i.name());
        assertEquals("", i.email());
        assertEquals("", i.institution());
    }

    @Test
    void anUnreadableTokenIsNoInfoAndNotSignedIn() throws Exception {
        store("not-a-jwt");
        assertEquals("not-a-jwt", Account.jwt(dir));
        assertNull(Account.info(dir));
        assertFalse(Account.signedIn(dir, NOW));
        Files.writeString(dir.resolve(Account.FILE), "{broken");
        assertNull(Account.jwt(dir));
    }

    @Test
    void signedInUntilTheTokenExpires() throws Exception {
        long exp = NOW.getEpochSecond();
        store(jwt("{\"plan\":\"account\",\"name\":\"Ada\",\"exp\":" + (exp + 1) + "}"));
        assertTrue(Account.signedIn(dir, NOW));
        store(jwt("{\"plan\":\"account\",\"name\":\"Ada\",\"exp\":" + exp + "}"));
        assertFalse(Account.signedIn(dir, NOW), "expired at its exp");
    }

    @Test
    void aTokenWithoutExpiryIsNotAcceptedAsSignedIn() throws Exception {
        store(jwt("{\"plan\":\"account\",\"name\":\"Ada\"}"));
        assertFalse(Account.signedIn(dir, NOW));
    }

    @Test
    void aLicenceAlwaysWinsOverAnAccountToken() {
        assertTrue(Account.counts(false, true));
        assertFalse(Account.counts(false, false));
        // A licence beside a leftover account token: the workstation stays what it is today.
        assertFalse(Account.counts(true, true));
        assertFalse(Account.counts(true, false));
    }

    @Test
    void anAccountIsNamedByItsNameThenItsEmail() {
        assertEquals("Ada", Account.displayName(new Account.Info("Ada", "ada@lab.org", "")));
        assertEquals("ada@lab.org", Account.displayName(new Account.Info(" ", "ada@lab.org", "")));
        assertEquals("", Account.displayName(new Account.Info("", "", "")));
        assertEquals("", Account.displayName(null));
    }
}
