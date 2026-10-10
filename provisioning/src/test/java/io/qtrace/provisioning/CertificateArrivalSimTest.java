package io.qtrace.provisioning;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless contract check of POST /api/account/certificate against the update-sim mock
 * (tools/update-sim/mock_server.py). Skipped unless {@code qtrace.sim} holds the mock's base URL;
 * {@code qtrace.sim.cert} says what the mock answers: {@code ready} (started with CERT_READY=1)
 * or {@code none} (default).
 * <pre>cd qtrace-core &amp;&amp; ./gradlew --offline -p provisioning test -Dqtrace.sim=http://127.0.0.1:8767 -Dqtrace.sim.cert=none</pre>
 */
class CertificateArrivalSimTest {

    private static final String SIM = System.getProperty("qtrace.sim");
    private static final String EXPECT = System.getProperty("qtrace.sim.cert", "none");

    /** An unsigned token of the shape the mock recognises as an account's (it reads the payload only). */
    private static String accountToken() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + b64.encodeToString("{\"plan\":\"account\"}".getBytes(StandardCharsets.UTF_8)) + ".x";
    }

    @Test
    void theMockAnswersWithTheCertificateOrTheMissingStep() {
        assumeTrue(SIM != null && !SIM.isBlank(), "qtrace.sim not set: no mock server to talk to");

        CertificateArrival.Decision d = CertificateArrival.decide(CertificateArrival.ask(SIM, accountToken()));
        if ("ready".equals(EXPECT)) {
            assertInstanceOf(CertificateArrival.Install.class, d, "the mock must run with CERT_READY=1");
        } else {
            assertEquals(new CertificateArrival.Wait("identity"), d);
        }
    }

    @Test
    void aTokenThatIsNotAnAccountsGetsNothing() {
        assumeTrue(SIM != null && !SIM.isBlank(), "qtrace.sim not set: no mock server to talk to");
        assertEquals(CertificateArrival.IGNORE, CertificateArrival.decide(CertificateArrival.ask(SIM, "not.an.account")));
    }
}
