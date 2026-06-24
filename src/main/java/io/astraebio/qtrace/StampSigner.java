/*
 * qTrace — QuPath workflow provenance extension
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

package io.astraebio.qtrace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Phase 19 — Signs a ValidationStamp payload with the validator's ED25519 private key.
 *
 * Key file format (downloaded from qtrace.ca):
 *   -----BEGIN QTRACE SIGNING KEY-----
 *   <base64url of PKCS8 DER-encoded ED25519 private key>
 *   -----END QTRACE SIGNING KEY-----
 *
 * Default location: ~/.qTrace/qtrace-signing.key
 * Configurable via QTraceConfig.getSigningKeyPath().
 */
public final class StampSigner {

    private StampSigner() {}

    /**
     * Signs the stamp's canonical payload and returns the signature as base64url.
     * Priority:
     *   1. In-memory decrypted key from Enterprise plugin (passphrase-unlocked).
     *   2. Key file on disk (legacy path via QTraceConfig / ~/.qTrace/qtrace-signing.key).
     * Returns null silently if no signing key is available.
     */
    public static String sign(ValidationStamp stamp) {
        // Enterprise installed but license inactive → degrade to Core: no certification at all.
        if (QTracePluginManager.hasEnterprise() && !QTracePluginManager.isEntitled()) return null;

        // 1. Try in-memory decrypted key (Enterprise, passphrase-protected .qtlicense)
        QTracePlugin ep = QTracePluginManager.getEntitled();
        if (ep != null) {
            String decryptedKey = ep.getDecryptedSigningKey();
            if (decryptedKey != null) {
                try {
                    byte[] derBytes = Base64.getUrlDecoder().decode(decryptedKey);
                    PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                        .generatePrivate(new PKCS8EncodedKeySpec(derBytes));
                    Signature sig = Signature.getInstance("Ed25519");
                    sig.initSign(privateKey);
                    sig.update(stamp.canonicalPayload().getBytes(StandardCharsets.UTF_8));
                    return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    System.err.println("[qTrace] StampSigner: in-memory key sign failed — " + msg);
                    javafx.application.Platform.runLater(() -> {
                        javafx.scene.control.Alert a = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.ERROR);
                        a.setTitle("qTrace — Signing error");
                        a.setHeaderText("Could not sign stamp with decrypted key");
                        a.setContentText(msg);
                        a.showAndWait();
                    });
                    return null;
                }
            } else if (ep.hasEncryptedSigningKey()) {
                System.err.println("[qTrace] StampSigner: passphrase not entered yet — stamp unsigned");
                return null;
            }
        }

        // 2. Fallback: key file on disk
        Path keyPath = resolveKeyPath();
        if (keyPath == null || !Files.exists(keyPath)) return null;

        try {
            byte[] derBytes = loadDer(keyPath);
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(derBytes));

            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(stamp.canonicalPayload().getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (Exception e) {
            System.err.println("[qTrace] StampSigner: could not sign stamp — " + e.getMessage());
            return null;
        }
    }

    /**
     * Verifies a signature against the canonical payload and the given base64url public key (SPKI-DER).
     * Returns false on any error.
     */
    public static boolean verify(ValidationStamp stamp, String pubKeyBase64url, String signatureBase64url) {
        if (pubKeyBase64url == null || signatureBase64url == null) return false;
        return verify(stamp.canonicalPayload(), pubKeyBase64url, signatureBase64url);
    }

    /**
     * Verifies a base64url ED25519 signature against a pre-computed canonical payload string.
     * Used by the Dashboard to verify stamps without reconstructing a full ValidationStamp.
     */
    public static boolean verify(String canonicalPayload, String pubKeyBase64url, String signatureBase64url) {
        if (canonicalPayload == null || pubKeyBase64url == null || signatureBase64url == null) return false;
        try {
            byte[] spkiDer  = Base64.getUrlDecoder().decode(pubKeyBase64url);
            byte[] sigBytes = Base64.getUrlDecoder().decode(signatureBase64url);
            java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(spkiDer);
            java.security.PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(spec);

            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));

            return sig.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static Path resolveKeyPath() {
        String configured = QTraceConfig.get().getSigningKeyPath();
        if (!configured.isBlank()) return Path.of(configured);
        return Path.of(System.getProperty("user.home"), ".qTrace", "qtrace-signing.key");
    }

    /** Parses the PEM-like wrapper and decodes the inner base64url content. */
    private static byte[] loadDer(Path keyPath) throws Exception {
        String content = Files.readString(keyPath).strip();
        String b64 = content
            .replace("-----BEGIN QTRACE SIGNING KEY-----", "")
            .replace("-----END QTRACE SIGNING KEY-----",   "")
            .replaceAll("\\s+", "");
        return Base64.getUrlDecoder().decode(b64);
    }
}
