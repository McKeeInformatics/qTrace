package io.qtrace.net;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A hospital network on loopback, for the tests: the qTrace server is only reachable through the
 * proxy (its name, {@code qtrace.invalid}, never resolves - RFC 2606 - except for the proxy), and
 * its HTTPS certificate is issued by the hospital's own inspection CA, which Java does not trust.
 * The proxy can ask for a Basic sign-in and block hosts, like a real one.
 */
final class HospitalNetwork implements AutoCloseable {

    static final String HOST = "qtrace.invalid";
    static final String PASS = "changeit";

    final Path dir;
    final Path caPem;
    final HttpsServer server;
    final HospitalProxy proxy;

    HospitalNetwork(Path dir) throws Exception {
        this.dir = dir;
        this.caPem = dir.resolve("hospital-ca.pem");
        makePki();
        this.server = startServer();
        this.proxy = new HospitalProxy(Map.of(HOST, server.getAddress().getPort()));
    }

    /** The qTrace server's URL, as qTrace would call it. */
    String url(String path) {
        return "https://" + HOST + ":" + server.getAddress().getPort() + path;
    }

    // ── PKI: a CA "Hospital Inspection CA", a server certificate for qtrace.invalid ──────

    private void makePki() throws Exception {
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=Hospital Inspection CA,O=Test Hospital", "-ext", "bc:c", "-validity", "3650",
            "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", PASS);
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PASS,
            "-file", "hospital-ca.pem");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=" + HOST, "-validity", "3650",
            "-keystore", "server.p12", "-storetype", "PKCS12", "-storepass", PASS);
        keytool("-certreq", "-alias", "server", "-keystore", "server.p12", "-storepass", PASS, "-file", "server.csr");
        keytool("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PASS, "-rfc",
            "-infile", "server.csr", "-outfile", "server.pem",
            "-ext", "san=dns:" + HOST, "-ext", "eku=serverAuth", "-validity", "3650");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", "hospital-ca.pem",
            "-keystore", "server.p12", "-storepass", PASS);
        keytool("-importcert", "-alias", "server", "-file", "server.pem",
            "-keystore", "server.p12", "-storepass", PASS);
    }

    private void keytool(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0)
            throw new IllegalStateException("keytool " + String.join(" ", args) + " failed: " + out);
    }

    private HttpsServer startServer() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve("server.p12"))) { ks.load(in, PASS.toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(ctx));
        s.createContext("/", ex -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        s.start();
        return s;
    }

    @Override
    public void close() {
        proxy.close();
        server.stop(0);
    }

    // ── The proxy ───────────────────────────────────────────────────────────────

    /** A CONNECT proxy: resolves the names it knows to loopback, can ask for Basic, can block. */
    static final class HospitalProxy implements AutoCloseable {
        private final Map<String, Integer> known;
        private final ServerSocket socket;
        final List<String> heads = Collections.synchronizedList(new ArrayList<>());
        volatile String requiredUser;   // "user:password" or null
        volatile Set<String> blocked = Set.of();

        HospitalProxy(Map<String, Integer> known) throws IOException {
            this.known = known;
            this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(this::loop);
        }

        int port() { return socket.getLocalPort(); }

        private void loop() {
            while (!socket.isClosed()) {
                try {
                    Socket c = socket.accept();
                    Thread.ofVirtual().start(() -> handle(c));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void handle(Socket c) {
            try (c) {
                byte[] head = ProxyRelay.readHead(c.getInputStream());
                if (head == null) return;
                String text = new String(head, StandardCharsets.ISO_8859_1);
                heads.add(text);
                String[] first = text.lines().findFirst().orElse("").split(" ");
                String host = ProxyRelay.hostOf(first[1]);
                if (requiredUser != null) {
                    String expected = "Proxy-Authorization: Basic "
                        + Base64.getEncoder().encodeToString(requiredUser.getBytes(StandardCharsets.UTF_8));
                    if (text.lines().noneMatch(l -> l.equalsIgnoreCase(expected) || l.equals(expected))) {
                        answer(c, "407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"hospital\"");
                        return;
                    }
                }
                if (blocked.contains(host)) { answer(c, "403 Forbidden"); return; }
                Integer port = known.get(host);
                if (port == null) { answer(c, "502 Bad Gateway"); return; }
                try (Socket up = new Socket(InetAddress.getLoopbackAddress(), port)) {
                    answer(c, "200 Connection established");
                    Thread down = Thread.ofVirtual().start(() -> copy(up, c));
                    copy(c, up);
                    down.join();
                }
            } catch (Exception ignored) {
            }
        }

        private static void answer(Socket c, String status) throws IOException {
            boolean ok = status.startsWith("200");
            c.getOutputStream().write(("HTTP/1.1 " + status + "\r\n" + (ok ? "" : "Content-Length: 0\r\n") + "\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
            c.getOutputStream().flush();
        }

        private static void copy(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
                to.shutdownOutput();
            } catch (IOException ignored) {
            }
        }

        @Override public void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
