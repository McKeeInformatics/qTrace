package io.qtrace.net;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot L7, end to end on loopback: a hospital whose only way out is a proxy, which inspects HTTPS
 * with its own CA. First what qTrace did before (every module built a bare HttpClient: no proxy
 * read, Java's certificates only), then what Settings › Network changes.
 */
class HospitalNetworkTest {

    @TempDir static Path dir;
    static HospitalNetwork net;
    static final Predicate<String> RELAY_HOSTS = h -> h.endsWith(".invalid");

    @BeforeAll
    static void up() throws Exception { net = new HospitalNetwork(dir); }

    @AfterAll
    static void down() { if (net != null) net.close(); }

    @BeforeEach
    void reset() {
        net.proxy.requiredUser = null;
        net.proxy.blocked = Set.of();
        net.proxy.heads.clear();
    }

    private static NetworkSettings manual(String user, String password, Path ca) {
        return new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, "127.0.0.1", net.proxy.port(),
            user, password, false, ca);
    }

    private static HttpResponse<String> get(NetworkSettings s) throws Exception {
        try (QTraceHttp.Network n = QTraceHttp.Network.build(s, RELAY_HOSTS)) {
            HttpClient c = n.newBuilder().build();
            return c.send(HttpRequest.newBuilder(URI.create(net.url("/api/version")))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static ConnectionTest.Outcome test(NetworkSettings s) {
        var target = new ConnectionTest.Target(HospitalNetwork.HOST, "network.target.server",
            URI.create(net.url("/api/version")));
        return ConnectionTest.check(s, target, RELAY_HOSTS);
    }

    // ── Before: what every module did ──────────────────────────────────────────

    @Test
    void before_aBareHttpClientCannotEvenFindTheServer() {
        HttpClient today = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        IOException e = assertThrows(IOException.class, () -> today.send(
            HttpRequest.newBuilder(URI.create(net.url("/api/version"))).GET().build(),
            HttpResponse.BodyHandlers.ofString()));
        assertTrue(e instanceof java.net.ConnectException, "no proxy is read: the name is resolved locally and fails - " + e);
        assertTrue(net.proxy.heads.isEmpty(), "nothing ever reached the proxy");
    }

    @Test
    void before_evenThroughTheProxy_JavaRejectsTheInspectionCertificate() {
        HttpClient viaProxyOnly = HttpClient.newBuilder()
            .proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress("127.0.0.1", net.proxy.port()))).build();
        IOException e = assertThrows(IOException.class, () -> viaProxyOnly.send(
            HttpRequest.newBuilder(URI.create(net.url("/api/version"))).GET().build(),
            HttpResponse.BodyHandlers.ofString()));
        assertInstanceOf(SSLHandshakeException.class, e, "PKIX path building failed");
    }

    // ── After: Settings › Network ──────────────────────────────────────────────

    @Test
    void manualProxy_andTheInstitutionCertificate_reachTheServer() throws Exception {
        HttpResponse<String> r = get(manual(null, null, net.caPem));
        assertEquals(200, r.statusCode());
        assertEquals("{\"ok\":true}", r.body());
        assertTrue(net.proxy.heads.get(0).startsWith("CONNECT " + HospitalNetwork.HOST + ":"));
    }

    @Test
    void theProxyAlone_stillRefusesTheUntrustedCertificate_andTheTestNamesItsIssuer() {
        assertThrows(SSLHandshakeException.class, () -> get(manual(null, null, null)));

        ConnectionTest.Outcome o = test(manual(null, null, null));
        assertEquals(ConnectionTest.Problem.CERTIFICATE, o.problem());
        assertEquals("Hospital Inspection CA (Test Hospital)", o.issuer());
        assertTrue(o.message().contains("Hospital Inspection CA"), o.message());
    }

    @Test
    void withTheCertificate_theTestSaysTheNetworkInspectsHttps() {
        ConnectionTest.Outcome o = test(manual(null, null, net.caPem));
        assertTrue(o.ok(), o.message());
        assertTrue(o.inspected(), "trusted only thanks to the institution certificate");
        assertEquals("Hospital Inspection CA (Test Hospital)", o.issuer());
    }

    @Test
    void aProxyAskingForBasicSignIn_isPassedWithTheUserNameAndPassword() throws Exception {
        net.proxy.requiredUser = "alice:s3cret é";
        assertEquals(407, get(manual(null, null, net.caPem)).statusCode(), "without credentials: 407");

        net.proxy.heads.clear();
        HttpResponse<String> r = get(manual("alice", "s3cret é", net.caPem));
        assertEquals(200, r.statusCode());
        assertTrue(net.proxy.heads.get(0).contains("Proxy-Authorization: Basic "), net.proxy.heads.get(0));
    }

    @Test
    void theTestExplainsSignIn_wrongPassword_andABlockedHost() {
        net.proxy.requiredUser = "alice:s3cret";
        assertEquals(ConnectionTest.Problem.PROXY_SIGN_IN, test(manual(null, null, net.caPem)).problem());
        ConnectionTest.Outcome wrong = test(manual("alice", "nope", net.caPem));
        assertEquals(ConnectionTest.Problem.PROXY_SIGN_IN, wrong.problem());
        assertEquals(io.qtrace.QTraceI18n.f("network.err.proxyauth.wrong", "127.0.0.1:" + net.proxy.port()), wrong.message());

        net.proxy.requiredUser = null;
        net.proxy.blocked = Set.of(HospitalNetwork.HOST);
        ConnectionTest.Outcome blocked = test(manual(null, null, net.caPem));
        assertEquals(ConnectionTest.Problem.PROXY_BLOCKED, blocked.problem());
        assertTrue(blocked.message().contains(HospitalNetwork.HOST), blocked.message());
    }

    @Test
    void theTestExplainsAWrongProxyAddress_andNoProxyAtAll() throws Exception {
        int closedPort;
        try (var s = new java.net.ServerSocket(0)) { closedPort = s.getLocalPort(); }
        var refused = new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, "127.0.0.1", closedPort, null, null, false, net.caPem);
        assertEquals(ConnectionTest.Problem.PROXY, test(refused).problem());

        var unknown = new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, "proxy.invalid", 3128, null, null, false, net.caPem);
        ConnectionTest.Outcome o = test(unknown);
        assertEquals(ConnectionTest.Problem.PROXY, o.problem());
        assertTrue(o.message().contains("proxy.invalid:3128"), o.message());

        var direct = new NetworkSettings(NetworkSettings.ProxyMode.NONE, null, 0, null, null, false, net.caPem);
        assertEquals(ConnectionTest.Problem.DNS, test(direct).problem());
    }

    @Test
    void theRelayOnlyOpensTunnelsToQtraceHosts() throws Exception {
        net.proxy.requiredUser = "alice:s3cret";
        try (QTraceHttp.Network n = QTraceHttp.Network.build(manual("alice", "s3cret", net.caPem), h -> false)) {
            HttpClient c = n.newBuilder().build();
            IOException e = assertThrows(IOException.class, () -> c.send(
                HttpRequest.newBuilder(URI.create(net.url("/api/version"))).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            assertTrue(e.getMessage().contains("403"), e.getMessage());
        }
        assertTrue(net.proxy.heads.isEmpty(), "the user's proxy account was not used");
    }

    @Test
    void aSettingsMistakeIsSaidBeforeAnyConnection() {
        var noHost = new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, " ", 3128, null, null, true, null);
        assertEquals(ConnectionTest.Problem.SETTINGS, test(noHost).problem());
        var url = new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, "http://proxy", 3128, null, null, true, null);
        assertEquals("settings.network.err.hostform", url.problem());
        var port = new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, "proxy", 0, null, null, true, null);
        assertEquals("settings.network.err.port", port.problem());
    }

    @Test
    void loopbackIsAlwaysDirect_soTheTestBenchKeepsWorking() {
        var s = manual(null, null, null);
        assertEquals(java.net.Proxy.NO_PROXY, QTraceHttp.routeFor(s, URI.create("http://127.0.0.1:8080/api")));
        assertEquals(java.net.Proxy.NO_PROXY, QTraceHttp.routeFor(s, URI.create("http://localhost/api")));
        assertNotEquals(java.net.Proxy.NO_PROXY, QTraceHttp.routeFor(s, URI.create("https://www.qtrace.ca/api")));
    }

    @Test
    void anUnreadableCertificateFile_doesNotBreakTheOtherRoutes() throws Exception {
        Path bad = dir.resolve("not-a-cert.pem");
        Files.writeString(bad, "hello");
        // Java's own certificates still work; the inspection certificate does not, so the
        // hospital server is refused - never accepted.
        assertThrows(SSLHandshakeException.class, () -> get(manual(null, null, bad)));
    }
}
