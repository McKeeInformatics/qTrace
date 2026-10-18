/*
 * qTrace - QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package io.qtrace.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Signs qTrace in to a proxy that asks for a user name and password (Basic).
 *
 * <p>Java's HTTP client will not send Basic credentials when it opens an HTTPS tunnel through a
 * proxy ({@code jdk.http.auth.tunneling.disabledSchemes=Basic}, fixed when the JVM starts, so
 * qTrace cannot change it inside QuPath). This relay, on the loopback interface only, receives the
 * client's {@code CONNECT host:443}, repeats it to the real proxy with a
 * {@code Proxy-Authorization} header, then copies bytes both ways. TLS still runs end to end
 * between qTrace and the server, through the relay: the relay sees only encrypted bytes, and the
 * server's certificate is checked by the client as on any other route.
 *
 * <p>Another program of this computer could connect to the relay too; it only opens tunnels to the
 * hosts {@code allowed} names (qTrace's own), so the user's proxy account cannot be used to reach
 * anything else. Only {@code CONNECT} is relayed.
 */
final class ProxyRelay implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ProxyRelay.class);
    private static final int MAX_HEADER = 16 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int HEADER_TIMEOUT_MS = 30_000;

    private final String upstreamHost;
    private final int upstreamPort;
    private final String authorization;
    private final Predicate<String> allowed;
    private final ServerSocket server;
    private volatile boolean closed;

    ProxyRelay(String upstreamHost, int upstreamPort, String user, String password, Predicate<String> allowed)
            throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
            (user + ":" + (password != null ? password : "")).getBytes(StandardCharsets.UTF_8));
        this.allowed = allowed;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("qtrace-proxy-relay").start(this::acceptLoop);
    }

    /** Where the HTTP client should send its CONNECT requests. */
    InetSocketAddress address() {
        return new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
    }

    @Override
    public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().name("qtrace-proxy-relay-conn").start(() -> handle(client));
            } catch (IOException e) {
                if (!closed) log.warn("Proxy relay stopped: {}", e.toString());
                return;
            }
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(HEADER_TIMEOUT_MS);
            byte[] head = readHead(client.getInputStream());
            String request = head == null ? "" : new String(head, StandardCharsets.ISO_8859_1);
            String first = request.lines().findFirst().orElse("");
            String[] parts = first.split(" ");
            if (parts.length < 3 || !parts[0].equals("CONNECT")) {
                reply(client, 405, "Only CONNECT");
                return;
            }
            String authority = parts[1];
            String host = hostOf(authority);
            if (host == null || !allowed.test(host.toLowerCase(Locale.ROOT))) {
                log.warn("Proxy relay: refused a tunnel to {} (not a qTrace host)", authority);
                reply(client, 403, "Not a qTrace host");
                return;
            }

            upstream = new Socket();
            upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), CONNECT_TIMEOUT_MS);
            upstream.setSoTimeout(HEADER_TIMEOUT_MS);
            OutputStream up = upstream.getOutputStream();
            up.write(("CONNECT " + authority + " HTTP/1.1\r\n"
                + "Host: " + authority + "\r\n"
                + "Proxy-Authorization: " + authorization + "\r\n"
                + "Proxy-Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            up.flush();

            InputStream upIn = upstream.getInputStream();
            byte[] answer = readHead(upIn);
            int status = statusOf(answer);
            if (status != 200) {
                // 407: wrong user name or password; 403: the proxy's rules refuse this host.
                reply(client, status > 0 ? status : 502, status > 0 ? "From proxy" : "Bad proxy answer");
                return;
            }
            client.getOutputStream().write(
                "HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
            client.setSoTimeout(0);
            upstream.setSoTimeout(0);

            Socket u = upstream;
            Thread back = Thread.ofVirtual().name("qtrace-proxy-relay-down").start(() -> pipe(upIn, client, u));
            pipe(client.getInputStream(), u, client);
            back.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.net.ConnectException | java.net.UnknownHostException | java.net.SocketTimeoutException e) {
            // The proxy itself cannot be reached: 502 tells the client the route failed.
            try { reply(client, 502, "Proxy unreachable"); } catch (IOException ignored) { }
            log.info("Proxy relay: proxy {}:{} unreachable ({})", upstreamHost, upstreamPort, e.toString());
        } catch (IOException e) {
            log.debug("Proxy relay connection ended: {}", e.toString());
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    /** Copies until end of stream, then half-closes the other side so the peer sees it. */
    private static void pipe(InputStream in, Socket to, Socket from) {
        byte[] buf = new byte[16 * 1024];
        try {
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
            try { to.shutdownOutput(); } catch (IOException ignored) { }
        } catch (SocketException e) {
            closeQuietly(to);
            closeQuietly(from);
        } catch (IOException e) {
            closeQuietly(to);
            closeQuietly(from);
        }
    }

    /** Reads up to the blank line ending an HTTP head; null when the stream ends first. */
    static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            state = switch (state) {
                case 0, 2 -> b == '\r' ? state + 1 : 0;
                case 1, 3 -> b == '\n' ? state + 1 : (b == '\r' ? 1 : 0);
                default -> 0;
            };
            if (state == 4) return buf.toByteArray();
        }
        throw new IOException("HTTP head too long");
    }

    static int statusOf(byte[] head) {
        if (head == null) return -1;
        String first = new String(head, StandardCharsets.ISO_8859_1).lines().findFirst().orElse("");
        String[] p = first.split(" ");
        if (p.length < 2 || !p[0].startsWith("HTTP/")) return -1;
        try { return Integer.parseInt(p[1]); } catch (NumberFormatException e) { return -1; }
    }

    static String hostOf(String authority) {
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            return end > 0 ? authority.substring(1, end) : null;
        }
        int colon = authority.lastIndexOf(':');
        return colon > 0 ? authority.substring(0, colon) : authority;
    }

    private static void reply(Socket client, int status, String reason) throws IOException {
        client.getOutputStream().write(("HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        client.getOutputStream().flush();
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }
}
