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

import io.qtrace.Account;
import io.qtrace.QTraceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The one place where qTrace builds an HTTP client. Every module calls {@link #newBuilder()}
 * instead of {@code HttpClient.newBuilder()}, so all of qTrace honours the network of the
 * institution as set in Settings › Network:
 * <ul>
 *   <li>the proxy: none, this computer's ({@link SystemProxy}, default) or a manual one, with an
 *       optional user name and password ({@link ProxyRelay});</li>
 *   <li>the certificates: Java's own, plus this computer's store and an institution root
 *       certificate when chosen ({@link TrustStores}). Certificate and host name verification
 *       are never switched off.</li>
 * </ul>
 * The builder comes with a 10 s connect timeout and the JDK's default redirect policy (never);
 * a caller that follows redirects keeps {@link HttpClient.Redirect#NORMAL}, which never follows
 * an https → http downgrade. Loopback addresses are always reached directly (the test bench).
 */
public final class QTraceHttp {

    private static final Logger log = LoggerFactory.getLogger(QTraceHttp.class);

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The Supabase project that stores uploads and module files (signed URLs). */
    public static final String STORAGE_HOST = "dljakxwhbjbzpyzklsjd.supabase.co";

    /** Every host the qTrace client contacts - the network sheet for IT lists the same. */
    public static final List<String> HOSTS = List.of(
        "www.qtrace.ca", "qtrace.ca", STORAGE_HOST,
        "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");

    private static volatile Network current;

    private QTraceHttp() {}

    /** A builder for this workstation's network: proxy, certificates, 10 s connect timeout. */
    public static HttpClient.Builder newBuilder() {
        return network().newBuilder();
    }

    /** The settings in force. */
    public static NetworkSettings settings() {
        return network().settings();
    }

    /** Reads the settings from {@code config.json} and the password file. */
    public static NetworkSettings load() {
        return NetworkSettingsStore.load(QTraceConfig.get(), Account.defaultDir());
    }

    /**
     * Makes {@code s} the settings of every client built from now on. Clients already built keep
     * theirs (each call builds its own, so this is the next request).
     */
    public static synchronized void apply(NetworkSettings s) {
        Network old = current;
        current = Network.build(s, QTraceHttp::isQtraceHost);
        if (old != null) old.close();
        log.info("Network settings: {}", s);
    }

    private static Network network() {
        Network n = current;
        if (n != null) return n;
        synchronized (QTraceHttp.class) {
            if (current == null) {
                NetworkSettings s;
                try {
                    s = load();
                } catch (RuntimeException | LinkageError e) {
                    s = NetworkSettings.defaults(); // outside QuPath (a module's tests)
                }
                current = Network.build(s, QTraceHttp::isQtraceHost);
            }
            return current;
        }
    }

    /** The hosts qTrace may tunnel to through the proxy relay: its own, and the configured server's. */
    static boolean isQtraceHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (HOSTS.contains(h) || h.endsWith(".qtrace.ca")) return true;
        String server = System.getProperty("qtrace.update.server");
        if (server != null) {
            try {
                String sh = URI.create(server).getHost();
                if (sh != null && sh.equalsIgnoreCase(h)) return true;
            } catch (IllegalArgumentException ignored) { }
        }
        return false;
    }

    /**
     * Where a request to {@code uri} goes with these settings: {@link Proxy#NO_PROXY} or the proxy
     * (for messages; the manual proxy, not the relay in front of it).
     */
    public static Proxy routeFor(NetworkSettings s, URI uri) {
        return switch (s.proxyMode()) {
            case NONE -> Proxy.NO_PROXY;
            case MANUAL -> SystemProxy.isLoopback(uri.getHost() == null ? "" : uri.getHost()) ? Proxy.NO_PROXY
                : new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(s.proxyHost(), s.proxyPort()));
            case SYSTEM -> SystemSelector.route(uri);
        };
    }

    // ── A built network: selector, SSL context, relay ─────────────────────────

    /** Settings turned into what a client needs. Package-private for the tests. */
    record Network(NetworkSettings settings, ProxySelector selector, SSLContext ssl, ProxyRelay relay,
                   X509ExtendedTrustManager trust) implements AutoCloseable {

        static Network build(NetworkSettings s, Predicate<String> relayHosts) {
            return build(s, relayHosts, UnaryOperator.identity());
        }

        /** {@code wrapTrust} lets the connection test see the certificates a server presents. */
        static Network build(NetworkSettings s, Predicate<String> relayHosts,
                             UnaryOperator<X509ExtendedTrustManager> wrapTrust) {
            X509ExtendedTrustManager tm;
            try {
                tm = TrustStores.trustManager(s);
            } catch (GeneralSecurityException | IOException e) {
                // The institution certificate is unreadable: carry on with the others, and say so.
                log.warn("Institution certificate {} not usable: {}", s.institutionCa(), e.toString());
                try {
                    tm = TrustStores.trustManager(s.withInstitutionCa(null));
                } catch (GeneralSecurityException | IOException e2) {
                    tm = TrustStores.jdkDefault();
                }
            }
            X509ExtendedTrustManager used = wrapTrust.apply(tm);
            SSLContext ssl;
            try {
                ssl = SSLContext.getInstance("TLS");
                ssl.init(null, new TrustManager[]{used}, null);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("TLS unavailable", e);
            }

            ProxyRelay relay = null;
            ProxySelector selector;
            switch (s.proxyMode()) {
                case NONE -> selector = HttpClient.Builder.NO_PROXY;
                case MANUAL -> {
                    InetSocketAddress target;
                    if (s.hasProxyCredentials()) {
                        try {
                            relay = new ProxyRelay(s.proxyHost(), s.proxyPort(), s.proxyUser(), s.proxyPassword(), relayHosts);
                            target = relay.address();
                        } catch (IOException e) {
                            log.warn("Could not start the proxy relay ({}); using the proxy without sign-in", e.toString());
                            target = new InetSocketAddress(s.proxyHost(), s.proxyPort());
                        }
                    } else {
                        target = InetSocketAddress.createUnresolved(s.proxyHost(), Math.max(1, s.proxyPort()));
                    }
                    selector = new FixedSelector(target);
                }
                default -> selector = new SystemSelector();
            }
            return new Network(s, selector, ssl, relay, used);
        }

        HttpClient.Builder newBuilder() {
            return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(selector)
                .sslContext(ssl);
        }

        @Override public void close() {
            if (relay != null) relay.close();
        }
    }

    /** Everything through one proxy, except loopback. */
    static final class FixedSelector extends ProxySelector {
        private final InetSocketAddress proxy;

        FixedSelector(InetSocketAddress proxy) { this.proxy = proxy; }

        @Override public List<Proxy> select(URI uri) {
            String host = uri.getHost();
            if (host == null || SystemProxy.isLoopback(host)) return List.of(Proxy.NO_PROXY);
            // An unresolved address is resolved by the client when it connects: a proxy whose
            // name the DNS does not know yet (VPN coming up) works once it does.
            InetSocketAddress a = proxy.isUnresolved() ? new InetSocketAddress(proxy.getHostString(), proxy.getPort()) : proxy;
            return List.of(new Proxy(Proxy.Type.HTTP, a));
        }

        @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
    }

    /** The JVM's own settings first (-Dhttps.proxyHost, useSystemProxies), then {@link SystemProxy}. */
    static final class SystemSelector extends ProxySelector {
        private static final Set<String> SCHEMES = Set.of("http", "https");

        @Override public List<Proxy> select(URI uri) {
            return List.of(route(uri));
        }

        static Proxy route(URI uri) {
            String host = uri.getHost();
            if (host == null || SystemProxy.isLoopback(host) || !SCHEMES.contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)))
                return Proxy.NO_PROXY;
            ProxySelector jvm = ProxySelector.getDefault();
            if (jvm != null) {
                try {
                    for (Proxy p : jvm.select(uri)) if (p.type() == Proxy.Type.HTTP) return p;
                } catch (RuntimeException ignored) { }
            }
            return SystemProxy.current().javaProxyFor(uri);
        }

        @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
    }
}
