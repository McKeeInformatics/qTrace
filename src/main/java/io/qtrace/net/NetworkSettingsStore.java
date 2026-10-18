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

import io.qtrace.QTraceConfig;
import io.qtrace.SecretFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

/**
 * Where {@link NetworkSettings} live:
 * <ul>
 *   <li>{@code config.json}: the proxy mode, host, port and user name, whether the computer's
 *       certificate store is trusted, and the path of qTrace's copy of the institution certificate;</li>
 *   <li>{@code ~/.qTrace/qtrace-proxy.secret}: the proxy password, written by {@link SecretFiles}
 *       (owner-only from its first byte), deleted as soon as no user name is set;</li>
 *   <li>{@code ~/.qTrace/institution-ca.pem}: a copy of the certificate file IT gave, so a file
 *       left on a network share or a USB key is not needed afterwards.</li>
 * </ul>
 */
public final class NetworkSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(NetworkSettingsStore.class);

    /** In {@link SecretFiles#NAMES}: tightened at start-up like the other secrets. */
    public static final String PASSWORD_FILE = "qtrace-proxy.secret";
    public static final String CA_FILE = "institution-ca.pem";

    private NetworkSettingsStore() {}

    public static NetworkSettings load(QTraceConfig cfg, Path dir) {
        NetworkSettings.ProxyMode mode = NetworkSettings.ProxyMode.parse(cfg.getNetworkProxyMode());
        String user = cfg.getNetworkProxyUser();
        String password = null;
        if (!user.isBlank()) {
            Path f = dir.resolve(PASSWORD_FILE);
            try {
                if (Files.isRegularFile(f)) password = Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Could not read the proxy password ({})", e.toString());
            }
        }
        String ca = cfg.getNetworkInstitutionCa();
        return new NetworkSettings(mode, cfg.getNetworkProxyHost(), cfg.getNetworkProxyPort(), user, password,
            cfg.isNetworkTrustSystemStore(), ca.isBlank() ? null : Path.of(ca));
    }

    /**
     * Writes {@code s} into {@code cfg} (the caller saves it) and the password file. The password is
     * kept only with a manual proxy and a user name; otherwise the file is removed.
     */
    public static void store(NetworkSettings s, QTraceConfig cfg, Path dir) throws IOException {
        cfg.setNetworkProxyMode(s.proxyMode().id());
        cfg.setNetworkProxyHost(s.proxyHost());
        cfg.setNetworkProxyPort(s.proxyPort());
        cfg.setNetworkProxyUser(s.proxyUser());
        cfg.setNetworkTrustSystemStore(s.trustSystemStore());
        cfg.setNetworkInstitutionCa(s.institutionCa() != null ? s.institutionCa().toString() : null);
        Path f = dir.resolve(PASSWORD_FILE);
        if (s.proxyUser() != null && s.proxyPassword() != null) SecretFiles.write(f, s.proxyPassword());
        else Files.deleteIfExists(f);
    }

    /**
     * Copies the certificate(s) of {@code chosen} (PEM or DER) into {@code dir/institution-ca.pem}
     * and returns that copy. Throws when the file holds no certificate.
     */
    public static Path importInstitutionCa(Path chosen, Path dir) throws IOException {
        List<X509Certificate> certs = TrustStores.readCertificates(chosen);
        StringBuilder pem = new StringBuilder();
        Base64.Encoder b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        try {
            for (X509Certificate c : certs) {
                pem.append("# ").append(c.getSubjectX500Principal().getName()).append('\n')
                   .append("-----BEGIN CERTIFICATE-----\n")
                   .append(b64.encodeToString(c.getEncoded())).append('\n')
                   .append("-----END CERTIFICATE-----\n");
            }
        } catch (CertificateEncodingException e) {
            throw new IOException(e);
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(CA_FILE);
        if (!chosen.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize()))
            Files.writeString(target, pem.toString(), StandardCharsets.US_ASCII);
        return target;
    }
}
