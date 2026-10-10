package io.qtrace.provisioning;

import io.qtrace.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless contract check of a basic-account sign-in against the update-sim mock server
 * (tools/update-sim/mock_server.py, started with ACCOUNT=1). Skipped unless the system property
 * {@code qtrace.sim} holds the mock's base URL:
 * <pre>cd qtrace-core &amp;&amp; ./gradlew --offline -p provisioning test -Dqtrace.sim=http://127.0.0.1:8766</pre>
 */
class AccountSignInSimTest {

    private static final String SIM = System.getProperty("qtrace.sim");

    @TempDir Path home;

    @Test
    void aSignInApprovedOnTheMockGivesAnAccountWeCanInstallAndRead() throws Exception {
        assumeTrue(SIM != null && !SIM.isBlank(), "qtrace.sim not set: no mock server to talk to");

        DeviceFlowClient flow = new DeviceFlowClient(SIM, millis -> { });
        DeviceFlowClient.Start start = flow.start(null);
        assertTrue(start.verificationUrl().startsWith(SIM.replaceAll("/+$", "")),
            "the /device page must be on the mock, not on another port: " + start.verificationUrl());

        // What the user's click on the mock /device page does.
        HttpResponse<String> approve = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(SIM.replaceAll("/+$", "") + "/device/approve"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    "{\"userCode\":\"" + start.userCode() + "\",\"decision\":\"approved\"}")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, approve.statusCode(), approve.body());

        DeviceFlowClient.Grant grant = flow.await(start, () -> false);
        assertEquals(DeviceFlowClient.Grant.Kind.ACCOUNT, grant.kind(),
            "the mock must run with ACCOUNT=1 (otherwise it hands over the maintainer's licence)");

        Path written = AccountInstaller.write(grant.payload(), home);
        assertEquals(home.resolve(Account.FILE), written);
        assertEquals("Ada Basic", Account.info(home).name());
        assertTrue(Account.signedIn(home, Instant.now()));
        assertFalse(java.nio.file.Files.exists(home.resolve("qtrace.qtlicense")), "no licence is written for an account");
    }
}
