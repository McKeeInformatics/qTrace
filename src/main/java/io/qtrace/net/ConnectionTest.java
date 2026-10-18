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

import io.qtrace.QTraceI18n;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * "Test connection to qtrace.ca" (Settings › Network): tries each host qTrace needs with the
 * settings on screen (saved or not) and says, in plain words, what stood in the way - the name
 * could not be resolved, the proxy refused, it asks for a sign-in, the certificate is not trusted
 * (with the name of whoever issued it, so IT recognises its inspection box). Nothing is sent but
 * an anonymous GET.
 */
public final class ConnectionTest {

    private ConnectionTest() {}

    /** One host to try: what it is for, and a URL that answers without sign-in. */
    public record Target(String host, String purposeKey, URI uri) {}

    /**
     * The result. {@code ok}: the host answered over a trusted connection. {@code message}: the
     * plain-words line. {@code issuer}: who issued the certificate the server presented (null when
     * none was seen). {@code inspected}: the connection is trusted only thanks to this computer's
     * store or the institution certificate - the network inspects HTTPS.
     */
    public record Outcome(Target target, Problem problem, String message, String issuer, boolean inspected) {
        public boolean ok() { return problem == Problem.NONE; }
    }

    /** What stood in the way, for the hint under the results and for the tests. */
    public enum Problem { NONE, SETTINGS, DNS, UNREACHABLE, PROXY, PROXY_SIGN_IN, PROXY_BLOCKED, CERTIFICATE, OTHER }

    record Diagnosis(Problem problem, String message) {}

    /** The hosts qTrace needs; the first is the qtrace.ca server in use. */
    public static List<Target> targets(String server) {
        URI s = URI.create(server.endsWith("/") ? server.substring(0, server.length() - 1) : server);
        List<Target> t = new ArrayList<>();
        t.add(new Target(s.getHost(), "network.target.server", URI.create(s + "/api/version")));
        t.add(new Target(QTraceHttp.STORAGE_HOST, "network.target.storage",
            URI.create("https://" + QTraceHttp.STORAGE_HOST + "/storage/v1/version")));
        t.add(new Target("github.com", "network.target.github",
            URI.create("https://github.com/RomainTourte/qTrace-core/releases/latest")));
        t.add(new Target("release-assets.githubusercontent.com", "network.target.githubfiles",
            URI.create("https://release-assets.githubusercontent.com/")));
        return t;
    }

    /** All the outcomes, and a hint when something failed and the cause is clear (null otherwise). */
    public record Report(List<Outcome> outcomes, String hint) {
        public boolean allOk() { return outcomes.stream().allMatch(Outcome::ok); }
    }

    public static Report run(NetworkSettings s, List<Target> targets) {
        SystemProxy.refresh();
        List<Outcome> out = new ArrayList<>();
        for (Target t : targets) out.add(check(s, t, QTraceHttp::isQtraceHost));
        String hint = null;
        if (!out.stream().allMatch(Outcome::ok)) {
            SystemProxy.Config sys = SystemProxy.current();
            if (s.proxyMode() == NetworkSettings.ProxyMode.SYSTEM && sys.hasAutomaticScript() && !sys.hasProxy()) {
                List<String> named = sys.pacUrl() != null ? proxiesNamedInScript(sys.pacUrl()) : List.of();
                hint = named.isEmpty()
                    ? QTraceI18n.f("network.hint.pac", sys.pacUrl() != null ? sys.pacUrl() : "WPAD")
                    : QTraceI18n.f("network.hint.pac.named", sys.pacUrl(), String.join(", ", named));
            } else if (out.stream().anyMatch(o -> o.problem() == Problem.CERTIFICATE)) {
                hint = QTraceI18n.f("network.hint.certificate");
            } else if (s.proxyMode() != NetworkSettings.ProxyMode.MANUAL
                       && out.stream().anyMatch(o -> o.problem() == Problem.DNS || o.problem() == Problem.UNREACHABLE)) {
                hint = QTraceI18n.f("network.hint.proxy");
            }
        }
        return new Report(out, hint);
    }

    /** Reads (never runs) the automatic configuration script, for the proxies it names. */
    private static List<String> proxiesNamedInScript(String pacUrl) {
        try {
            URI u = URI.create(pacUrl);
            if (!"http".equalsIgnoreCase(u.getScheme()) && !"https".equalsIgnoreCase(u.getScheme())) return List.of();
            HttpClient c = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? SystemProxy.proxiesNamedIn(r.body()) : List.of();
        } catch (IOException | IllegalArgumentException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Tries one host. Package-private with {@code relayHosts} for the tests. */
    static Outcome check(NetworkSettings s, Target t, Predicate<String> relayHosts) {
        String problem = s.problem();
        if (!problem.isEmpty()) return new Outcome(t, Problem.SETTINGS, QTraceI18n.f(problem), null, false);

        Proxy route = QTraceHttp.routeFor(s, t.uri());
        String proxyName = route.type() == Proxy.Type.HTTP ? name(route) : null;

        // A proxy that cannot be reached at all is said as such, before any HTTPS.
        if (proxyName != null) {
            Diagnosis unreachable = proxyUnreachable(route);
            if (unreachable != null) return new Outcome(t, unreachable.problem(), unreachable.message(), null, false);
        }

        AtomicReference<X509Certificate[]> seen = new AtomicReference<>();
        X509ExtendedTrustManager jdk = TrustStores.jdkDefault();
        try (QTraceHttp.Network n = QTraceHttp.Network.build(s, relayHosts, tm -> new Recording(tm, seen))) {
            HttpClient client = n.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<Void> r = client.send(HttpRequest.newBuilder(t.uri())
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() == 407) {
                return fail(t, new Diagnosis(Problem.PROXY_SIGN_IN, s.hasProxyCredentials()
                    ? QTraceI18n.f("network.err.proxyauth.wrong", proxyName)
                    : QTraceI18n.f("network.err.proxyauth.needed", proxyName)), seen);
            }
            String issuer = issuer(seen.get());
            boolean inspected = seen.get() != null && !trustedBy(jdk, seen.get());
            String via = proxyName != null ? QTraceI18n.f("network.via.proxy", proxyName) : QTraceI18n.f("network.via.direct");
            String msg = QTraceI18n.f("network.ok", via, issuer != null ? issuer : "?");
            if (inspected) msg += " " + QTraceI18n.f("network.ok.inspected");
            return new Outcome(t, Problem.NONE, msg, issuer, inspected);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(t, new Diagnosis(Problem.OTHER, QTraceI18n.f("network.err.interrupted")), seen);
        } catch (IOException e) {
            return fail(t, explain(e, t, proxyName, s, seen.get()), seen);
        }
    }

    private static Outcome fail(Target t, Diagnosis d, AtomicReference<X509Certificate[]> seen) {
        return new Outcome(t, d.problem(), d.message(), issuer(seen.get()), false);
    }

    /** The plain-words reason of a failed request. Package-private for the tests. */
    static Diagnosis explain(IOException e, Target t, String proxyName, NetworkSettings s, X509Certificate[] chain) {
        Throwable cert = find(e, CertificateException.class);
        if (cert != null || find(e, SSLHandshakeException.class) != null) {
            String m = String.valueOf(cert != null ? cert.getMessage() : e.getMessage());
            String issuer = issuer(chain);
            if (m.contains("No subject alternative") || m.contains("No name matching"))
                return new Diagnosis(Problem.CERTIFICATE, QTraceI18n.f("network.err.certname", t.host(), issuer != null ? issuer : "?"));
            if (cert != null || m.contains("PKIX") || m.contains("certification path")) {
                return new Diagnosis(Problem.CERTIFICATE, issuer != null
                    ? QTraceI18n.f("network.err.untrusted", t.host(), issuer)
                    : QTraceI18n.f("network.err.untrusted.unknown", t.host()));
            }
        }
        String msg = String.valueOf(e.getMessage());
        if (msg.startsWith("Tunnel failed, got: ")) {
            String code = msg.substring("Tunnel failed, got: ".length()).strip();
            return switch (code) {
                case "403" -> new Diagnosis(Problem.PROXY_BLOCKED, QTraceI18n.f("network.err.proxyblocked", proxyName, t.host()));
                case "407" -> new Diagnosis(Problem.PROXY_SIGN_IN, QTraceI18n.f(s.hasProxyCredentials()
                    ? "network.err.proxyauth.wrong" : "network.err.proxyauth.needed", proxyName));
                default -> new Diagnosis(Problem.PROXY, QTraceI18n.f("network.err.proxyupstream", proxyName, t.host(), code));
            };
        }
        if (find(e, UnresolvedAddressException.class) != null || find(e, UnknownHostException.class) != null) {
            return proxyName != null
                ? new Diagnosis(Problem.PROXY, QTraceI18n.f("network.err.proxydns", proxyName))
                : new Diagnosis(Problem.DNS, QTraceI18n.f("network.err.dns", t.host()));
        }
        if (e instanceof HttpConnectTimeoutException || find(e, SocketTimeoutException.class) != null) {
            return proxyName != null
                ? new Diagnosis(Problem.PROXY, QTraceI18n.f("network.err.proxytimeout", proxyName))
                : new Diagnosis(Problem.UNREACHABLE, QTraceI18n.f("network.err.timeout", t.host()));
        }
        if (e instanceof HttpTimeoutException) return new Diagnosis(Problem.OTHER, QTraceI18n.f("network.err.slow", t.host()));
        if (find(e, ConnectException.class) != null) {
            return proxyName != null
                ? new Diagnosis(Problem.PROXY, QTraceI18n.f("network.err.proxyrefused", proxyName))
                : new Diagnosis(Problem.UNREACHABLE, QTraceI18n.f("network.err.refused", t.host()));
        }
        SSLException ssl = find(e, SSLException.class);
        if (ssl != null) return new Diagnosis(Problem.OTHER, QTraceI18n.f("network.err.tls", t.host(), String.valueOf(ssl.getMessage())));
        return new Diagnosis(Problem.OTHER, QTraceI18n.f("network.err.other", t.host(),
            e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")));
    }

    /** Null when a TCP connection to the proxy opens; otherwise why it does not. */
    private static Diagnosis proxyUnreachable(Proxy route) {
        InetSocketAddress a = (InetSocketAddress) route.address();
        String name = name(route);
        InetSocketAddress resolved = new InetSocketAddress(a.getHostString(), a.getPort());
        if (resolved.isUnresolved()) return new Diagnosis(Problem.PROXY, QTraceI18n.f("network.err.proxydns", name));
        try (Socket sock = new Socket()) {
            sock.connect(resolved, 5000);
            return null;
        } catch (SocketTimeoutException e) {
            return new Diagnosis(Problem.PROXY, QTraceI18n.f("network.err.proxytimeout", name));
        } catch (IOException e) {
            return new Diagnosis(Problem.PROXY, QTraceI18n.f("network.err.proxyrefused", name));
        }
    }

    private static String name(Proxy p) {
        InetSocketAddress a = (InetSocketAddress) p.address();
        return a.getHostString() + ":" + a.getPort();
    }

    /** Who issued the top of the chain: the root, or the inspection box's own CA. */
    static String issuer(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) return null;
        return TrustStores.shortName(chain[chain.length - 1].getIssuerX500Principal());
    }

    private static boolean trustedBy(X509ExtendedTrustManager tm, X509Certificate[] chain) {
        try {
            tm.checkServerTrusted(chain, chain[0].getPublicKey().getAlgorithm().equals("EC") ? "ECDHE_ECDSA" : "ECDHE_RSA");
            return true;
        } catch (CertificateException | RuntimeException e) {
            return false;
        }
    }

    private static <T extends Throwable> T find(Throwable e, Class<T> type) {
        for (Throwable c = e; c != null; c = c.getCause()) if (type.isInstance(c)) return type.cast(c);
        return null;
    }

    /** Remembers the chain the server presented, then lets the real trust manager decide. */
    static final class Recording extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager tm;
        private final AtomicReference<X509Certificate[]> seen;

        Recording(X509ExtendedTrustManager tm, AtomicReference<X509Certificate[]> seen) {
            this.tm = tm;
            this.seen = seen;
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            seen.set(chain);
            tm.checkServerTrusted(chain, authType, engine);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            seen.set(chain);
            tm.checkServerTrusted(chain, authType, socket);
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            seen.set(chain);
            tm.checkServerTrusted(chain, authType);
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            tm.checkClientTrusted(chain, authType, engine);
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            tm.checkClientTrusted(chain, authType, socket);
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            tm.checkClientTrusted(chain, authType);
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return tm.getAcceptedIssuers(); }
    }
}
