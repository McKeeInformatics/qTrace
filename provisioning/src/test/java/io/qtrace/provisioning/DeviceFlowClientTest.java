package io.qtrace.provisioning;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** "Sign in to qtrace.ca" (loader.md § 17) against a fake portal. */
class DeviceFlowClientTest {

    private HttpServer server;
    private String base;
    private final Deque<String[]> pollAnswers = new ArrayDeque<>(); // {httpStatus, body}
    private final List<String> pollBodies = new ArrayList<>();
    private final List<String> startBodies = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/device/start", ex -> {
            startBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = ("{\"deviceCode\":\"dev-secret\",\"userCode\":\"ABCD-EFGH\","
                + "\"verificationUrl\":\"https://qtrace.test/portal/device?code=ABCD-EFGH\","
                + "\"interval\":5,\"expiresIn\":600}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/device/poll", ex -> {
            pollBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String[] a = pollAnswers.isEmpty() ? new String[]{"200", "{\"status\":\"pending\"}"} : pollAnswers.poll();
            byte[] b = a[1].getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(Integer.parseInt(a[0]), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private final List<Long> slept = new ArrayList<>();
    private DeviceFlowClient client() { return new DeviceFlowClient(base, ms -> slept.add(ms)); }

    @Test
    void startReturnsTheCodes() throws Exception {
        DeviceFlowClient.Start s = client().start(null);
        assertEquals("dev-secret", s.deviceCode());
        assertEquals("ABCD-EFGH", s.userCode());
        assertEquals("https://qtrace.test/portal/device?code=ABCD-EFGH", s.verificationUrl());
        assertEquals(5, s.interval());
        assertEquals(600, s.expiresIn());
    }

    @Test
    void theInvitationCodeGoesWithTheSignIn() throws Exception {
        client().start("WXYZ-2345-6789");
        client().start(null);
        assertEquals(List.of("{\"invite\":\"WXYZ-2345-6789\"}", "{}"), startBodies);
    }

    @Test
    void waitsUntilApprovedThenReturnsTheLicenseEnvelope() throws Exception {
        pollAnswers.add(new String[]{"200", "{\"status\":\"pending\"}"});
        pollAnswers.add(new String[]{"200", "{\"status\":\"approved\",\"license\":{\"jwt\":\"a.b.c\",\"sk_enc\":\"E\",\"sk_salt\":\"S\",\"sk_iv\":\"I\"}}"});
        DeviceFlowClient c = client();
        DeviceFlowClient.Grant grant = c.await(c.start(null), () -> false);
        assertEquals(DeviceFlowClient.Grant.Kind.LICENSE, grant.kind());
        String license = grant.payload();
        assertTrue(license.contains("\"jwt\":\"a.b.c\""));
        assertTrue(license.contains("\"sk_enc\":\"E\""));
        assertEquals(2, pollBodies.size());
        assertTrue(pollBodies.get(0).contains("\"deviceCode\":\"dev-secret\""));
        assertEquals(List.of(5000L, 5000L), slept, "waits the server interval before each poll");
    }

    @Test
    void aBasicAccountGetsItsTokenInsteadOfALicense() throws Exception {
        pollAnswers.add(new String[]{"200", "{\"status\":\"approved\",\"account\":{\"jwt\":\"x.y.z\"}}"});
        DeviceFlowClient c = client();
        DeviceFlowClient.Grant grant = c.await(c.start(null), () -> false);
        assertEquals(DeviceFlowClient.Grant.Kind.ACCOUNT, grant.kind());
        assertEquals("x.y.z", grant.payload());
    }

    @Test
    void aLicenseWinsWhenTheAnswerCarriesBoth() throws Exception {
        pollAnswers.add(new String[]{"200", "{\"status\":\"approved\",\"license\":{\"jwt\":\"a.b.c\"},\"account\":{\"jwt\":\"x.y.z\"}}"});
        DeviceFlowClient c = client();
        assertEquals(DeviceFlowClient.Grant.Kind.LICENSE, c.await(c.start(null), () -> false).kind());
    }

    @Test
    void approvedWithNeitherIsAnError() {
        pollAnswers.add(new String[]{"200", "{\"status\":\"approved\"}"});
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.await(c.start(null), () -> false));
        assertEquals(DeviceFlowClient.Outcome.ERROR, e.outcome());
        pollAnswers.add(new String[]{"200", "{\"status\":\"approved\",\"account\":{\"jwt\":\"\"}}"});
        var e2 = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.await(c.start(null), () -> false));
        assertEquals(DeviceFlowClient.Outcome.ERROR, e2.outcome());
    }

    @Test
    void backsOffWhenTheServerSaysSlowDown() throws Exception {
        pollAnswers.add(new String[]{"429", "{\"status\":\"slow_down\"}"});
        pollAnswers.add(new String[]{"200", "{\"status\":\"approved\",\"license\":{\"jwt\":\"a.b.c\"}}"});
        DeviceFlowClient c = client();
        c.await(c.start(null), () -> false);
        assertEquals(List.of(5000L, 10000L), slept);
    }

    @Test
    void reportsADenial() {
        pollAnswers.add(new String[]{"200", "{\"status\":\"denied\"}"});
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.await(c.start(null), () -> false));
        assertEquals(DeviceFlowClient.Outcome.DENIED, e.outcome());
    }

    @Test
    void reportsAnExpiredCode() {
        pollAnswers.add(new String[]{"404", "{\"status\":\"expired\"}"});
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.await(c.start(null), () -> false));
        assertEquals(DeviceFlowClient.Outcome.EXPIRED, e.outcome());
    }

    @Test
    void stopsWhenTheUserCancels() {
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.await(c.start(null), () -> true));
        assertEquals(DeviceFlowClient.Outcome.CANCELLED, e.outcome());
        assertTrue(pollBodies.isEmpty());
    }

    @Test
    void givesUpAfterTheExpiryDelay() {
        // interval 5 s, expiresIn 600 s (this fake server's value) → at most 120 polls, then EXPIRED
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.await(c.start(null), () -> false));
        assertEquals(DeviceFlowClient.Outcome.EXPIRED, e.outcome());
        assertEquals(120, pollBodies.size());
    }
}
