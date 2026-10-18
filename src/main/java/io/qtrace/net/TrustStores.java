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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The certificates qTrace trusts for HTTPS: always Java's own (the {@code cacerts} of QuPath's
 * runtime), plus - when chosen - this computer's store (where an IT department installs the root
 * of its TLS inspection box) and one certificate file IT gives.
 *
 * Every source is a regular PKIX trust manager from the JDK: the chain, its signatures, its dates
 * and the host name are checked exactly as for Java's own roots. {@link Composite} only accepts a
 * chain that one of them accepts; there is no "trust everything" path anywhere.
 */
public final class TrustStores {

    private static final Logger log = LoggerFactory.getLogger(TrustStores.class);

    /** The Linux bundles that {@code update-ca-certificates} / {@code update-ca-trust} maintain. */
    static final List<Path> LINUX_BUNDLES = List.of(
        Path.of("/etc/ssl/certs/ca-certificates.crt"),       // Debian, Ubuntu, Arch
        Path.of("/etc/pki/tls/certs/ca-bundle.crt"),         // Fedora, RHEL
        Path.of("/etc/ssl/ca-bundle.pem"),                   // openSUSE
        Path.of("/etc/ssl/cert.pem"));                       // Alpine, macOS-like

    private TrustStores() {}

    /** What this computer's store gave: a name for messages, and its certificates (empty when unreadable). */
    public record SystemStore(String name, List<X509Certificate> certificates, String error) {
        public boolean available() { return !certificates.isEmpty(); }
    }

    /** Java's own trust manager: QuPath runtime's cacerts (or -Djavax.net.ssl.trustStore). */
    public static X509ExtendedTrustManager jdkDefault() {
        try {
            return forKeyStore(null);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No default trust manager", e);
        }
    }

    /** A PKIX trust manager whose anchors are {@code certificates}. */
    public static X509ExtendedTrustManager forCertificates(Collection<X509Certificate> certificates)
            throws GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            ks.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        int i = 0;
        for (X509Certificate c : certificates) ks.setCertificateEntry("anchor-" + (i++), c);
        return forKeyStore(ks);
    }

    private static X509ExtendedTrustManager forKeyStore(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers())
            if (tm instanceof X509ExtendedTrustManager x) return x;
        throw new GeneralSecurityException("No X509 trust manager");
    }

    /**
     * The trust manager for {@code s}: Java's own, then the computer's store, then the institution
     * certificate. Throws when the institution certificate cannot be read (a setting that silently
     * does nothing would send the user looking elsewhere).
     */
    public static X509ExtendedTrustManager trustManager(NetworkSettings s) throws GeneralSecurityException, IOException {
        List<X509ExtendedTrustManager> tms = new ArrayList<>();
        tms.add(jdkDefault());
        if (s.trustSystemStore()) {
            SystemStore store = systemStore();
            if (store.available()) tms.add(forCertificates(store.certificates()));
        }
        if (s.institutionCa() != null) tms.add(forCertificates(readCertificates(s.institutionCa())));
        return tms.size() == 1 ? tms.get(0) : new Composite(tms);
    }

    private static volatile SystemStore cachedStore;

    /** This computer's store, read once (Windows, macOS, or the Linux bundle). */
    public static SystemStore systemStore() {
        SystemStore s = cachedStore;
        if (s == null) cachedStore = s = readSystemStore(System.getProperty("os.name", ""));
        return s;
    }

    static SystemStore readSystemStore(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // Windows-ROOT also lists the machine's roots (those a GPO deploys). Needs the
            // jdk.crypto.mscapi module, which a trimmed runtime may leave out.
            return fromKeyStores("Windows", "Windows-ROOT", "Windows-ROOT-LOCALMACHINE");
        }
        if (os.contains("mac")) {
            // KeychainStore-ROOT (JDK 23+) holds the system and admin roots; KeychainStore the user's.
            return fromKeyStores("macOS", "KeychainStore-ROOT", "KeychainStore");
        }
        for (Path bundle : LINUX_BUNDLES) {
            if (!Files.isRegularFile(bundle)) continue;
            try {
                return new SystemStore(bundle.toString(), readCertificates(bundle), null);
            } catch (IOException e) {
                return new SystemStore(bundle.toString(), List.of(), e.getMessage());
            }
        }
        return new SystemStore("system", List.of(), "no system certificate bundle found");
    }

    private static SystemStore fromKeyStores(String name, String... types) {
        Map<String, X509Certificate> certs = new LinkedHashMap<>();
        String error = null;
        for (String type : types) {
            try {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(null, null);
                for (String alias : java.util.Collections.list(ks.aliases())) {
                    if (ks.getCertificate(alias) instanceof X509Certificate c)
                        certs.putIfAbsent(c.getSubjectX500Principal().getName() + "|" + c.getSerialNumber(), c);
                }
            } catch (Exception e) {
                if (error == null) error = type + ": " + e.getMessage();
            }
        }
        if (certs.isEmpty() && error != null) log.info("Certificate store of {} not readable ({})", name, error);
        return new SystemStore(name, List.copyOf(certs.values()), certs.isEmpty() ? error : null);
    }

    /** The certificates of a PEM (one or more) or DER file. Throws when it holds none. */
    public static List<X509Certificate> readCertificates(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> out = new ArrayList<>();
            for (var c : cf.generateCertificates(new ByteArrayInputStream(bytes)))
                if (c instanceof X509Certificate x) out.add(x);
            if (out.isEmpty()) throw new IOException("no certificate in " + file.getFileName());
            return out;
        } catch (CertificateException e) {
            throw new IOException("not a certificate file: " + file.getFileName(), e);
        }
    }

    /** "Common name (organisation)" of a distinguished name, the way IT recognises its box. */
    public static String shortName(javax.security.auth.x500.X500Principal p) {
        String cn = null, o = null;
        for (String[] rdn : rdns(p.getName(javax.security.auth.x500.X500Principal.RFC2253))) {
            if (rdn[0].equalsIgnoreCase("CN") && cn == null) cn = rdn[1];
            if (rdn[0].equalsIgnoreCase("O") && o == null) o = rdn[1];
        }
        if (cn == null && o == null) return p.getName();
        if (cn == null) return o;
        return o == null || cn.contains(o) ? cn : cn + " (" + o + ")";
    }

    /** type=value pairs of an RFC 2253 name, escapes undone (no javax.naming: not in every runtime). */
    static List<String[]> rdns(String dn) {
        List<String[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String type = null;
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\' && i + 1 < dn.length()) {
                cur.append(dn.charAt(++i));
            } else if (c == '=' && type == null) {
                type = cur.toString().strip();
                cur.setLength(0);
            } else if (c == ',' || c == '+') {
                if (type != null) out.add(new String[]{type, cur.toString().strip()});
                type = null;
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (type != null) out.add(new String[]{type, cur.toString().strip()});
        return out;
    }

    /**
     * Accepts a chain when one of its trust managers does - each a full PKIX check, host name
     * included. When none does, the first one's error (Java's own) is thrown.
     */
    static final class Composite extends X509ExtendedTrustManager {
        private final List<X509ExtendedTrustManager> tms;

        Composite(List<X509ExtendedTrustManager> tms) { this.tms = List.copyOf(tms); }

        private interface Check { void run(X509ExtendedTrustManager tm) throws CertificateException; }

        private void any(Check check) throws CertificateException {
            CertificateException first = null;
            for (X509ExtendedTrustManager tm : tms) {
                try {
                    check.run(tm);
                    return;
                } catch (CertificateException e) {
                    if (first == null) first = e;
                }
            }
            throw first != null ? first : new CertificateException("no trust manager");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType));
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType, socket));
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType, engine));
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType));
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType, socket));
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            any(tm -> tm.checkClientTrusted(chain, authType, engine));
        }
        @Override public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509ExtendedTrustManager tm : tms) all.addAll(List.of(tm.getAcceptedIssuers()));
            return all.toArray(new X509Certificate[0]);
        }
    }
}
