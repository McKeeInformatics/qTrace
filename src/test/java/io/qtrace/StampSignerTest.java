package io.qtrace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip test for the generalized signPayload/verify pair — exercises the file-key
 * fallback path (no Compliance plugin registered in a bare test JVM, so
 * QTracePluginManager.hasCompliance() is false and signPayload falls through to it).
 */
class StampSignerTest {

    private String originalSigningKeyPath;

    @BeforeEach
    void saveConfig() {
        originalSigningKeyPath = QTraceConfig.get().getSigningKeyPath();
    }

    @AfterEach
    void restoreConfig() {
        QTraceConfig.get().setSigningKeyPath(originalSigningKeyPath);
    }

    @Test
    void signPayload_thenVerify_roundTrips(@TempDir Path tempDir) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyFile = writeKeyFile(tempDir, keyPair);
        QTraceConfig.get().setSigningKeyPath(keyFile.toString());

        String payload = "{\"hello\":\"world\"}";
        String signature = StampSigner.signPayload(payload);
        assertNotNull(signature);

        String pubKeyB64url = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(keyPair.getPublic().getEncoded());
        assertTrue(StampSigner.verify(payload, pubKeyB64url, signature));
        assertFalse(StampSigner.verify(payload + "tampered", pubKeyB64url, signature));
    }

    @Test
    void replayStamp_canonicalPayload_signsAndVerifies(@TempDir Path tempDir) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyFile = writeKeyFile(tempDir, keyPair);
        QTraceConfig.get().setSigningKeyPath(keyFile.toString());

        String pubKeyB64url = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(keyPair.getPublic().getEncoded());

        ReplayStamp unsigned = new ReplayStamp(
            "Dr. Test", Instant.parse("2026-08-11T10:00:00Z"), "sourcehash123", "loghash456",
            "0.7.0", "OK", 3, 3, 0, 0, 0, List.of(), null, pubKeyB64url);

        String signature = StampSigner.signPayload(unsigned.canonicalPayload());
        assertNotNull(signature);
        assertTrue(StampSigner.verify(unsigned.canonicalPayload(), pubKeyB64url, signature));
    }

    private static Path writeKeyFile(Path dir, KeyPair keyPair) throws Exception {
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.getPrivate().getEncoded());
        Path keyFile = dir.resolve("test-signing.key");
        Files.writeString(keyFile,
            "-----BEGIN QTRACE SIGNING KEY-----\n" + b64 + "\n-----END QTRACE SIGNING KEY-----\n");
        return keyFile;
    }
}
