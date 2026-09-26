package io.qtrace.onboarding;

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

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/device/start", ex -> {
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
        DeviceFlowClient.Start s = client().start();
        assertEquals("dev-secret", s.deviceCode());
        assertEquals("ABCD-EFGH", s.userCode());
        assertEquals("https://qtrace.test/portal/device?code=ABCD-EFGH", s.verificationUrl());
        assertEquals(5, s.interval());
        assertEquals(600, s.expiresIn());
    }

    @Test
    void waitsUntilApprovedThenReturnsTheLicenseEnvelope() throws Exception {
        pollAnswers.add(new String[]{"200", "{\"status\":\"pending\"}"});
        pollAnswers.add(new String[]{"200", "{\"status\":\"approved\",\"license\":{\"jwt\":\"a.b.c\",\"sk_enc\":\"E\",\"sk_salt\":\"S\",\"sk_iv\":\"I\"}}"});
        DeviceFlowClient c = client();
        String license = c.awaitLicense(c.start(), () -> false);
        assertTrue(license.contains("\"jwt\":\"a.b.c\""));
        assertTrue(license.contains("\"sk_enc\":\"E\""));
        assertEquals(2, pollBodies.size());
        assertTrue(pollBodies.get(0).contains("\"deviceCode\":\"dev-secret\""));
        assertEquals(List.of(5000L, 5000L), slept, "waits the server interval before each poll");
    }

    @Test
    void backsOffWhenTheServerSaysSlowDown() throws Exception {
        pollAnswers.add(new String[]{"429", "{\"status\":\"slow_down\"}"});
        pollAnswers.add(new String[]{"200", "{\"status\":\"approved\",\"license\":{\"jwt\":\"a.b.c\"}}"});
        DeviceFlowClient c = client();
        c.awaitLicense(c.start(), () -> false);
        assertEquals(List.of(5000L, 10000L), slept);
    }

    @Test
    void reportsADenial() {
        pollAnswers.add(new String[]{"200", "{\"status\":\"denied\"}"});
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.awaitLicense(c.start(), () -> false));
        assertEquals(DeviceFlowClient.Outcome.DENIED, e.outcome());
    }

    @Test
    void reportsAnExpiredCode() {
        pollAnswers.add(new String[]{"404", "{\"status\":\"expired\"}"});
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.awaitLicense(c.start(), () -> false));
        assertEquals(DeviceFlowClient.Outcome.EXPIRED, e.outcome());
    }

    @Test
    void stopsWhenTheUserCancels() {
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.awaitLicense(c.start(), () -> true));
        assertEquals(DeviceFlowClient.Outcome.CANCELLED, e.outcome());
        assertTrue(pollBodies.isEmpty());
    }

    @Test
    void givesUpAfterTheExpiryDelay() {
        // interval 5 s, expiresIn 600 s → at most 120 polls, then EXPIRED even if still pending
        DeviceFlowClient c = client();
        var e = assertThrows(DeviceFlowClient.DeviceFlowException.class, () -> c.awaitLicense(c.start(), () -> false));
        assertEquals(DeviceFlowClient.Outcome.EXPIRED, e.outcome());
        assertEquals(120, pollBodies.size());
    }
}
