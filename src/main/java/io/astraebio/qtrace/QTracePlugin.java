package io.astraebio.qtrace;

import com.google.gson.JsonObject;
import qupath.lib.gui.QuPathGUI;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface implemented by the Enterprise module.
 * Discovered at runtime via ServiceLoader — if no enterprise JAR is present,
 * QTracePluginManager.get() returns null and premium features are silently absent.
 *
 * Enterprise features: cryptographic certification (Ed25519 + OpenTimestamps),
 * contributor identity verification, blockchain anchoring (planned v0.7.0).
 * All methods are default no-ops for forward compatibility.
 */
public interface QTracePlugin {
    default void certifyStamp(ValidationStamp stamp, QuPathGUI qupath) {}
    default void verifyContributor(String contributorId, QuPathGUI qupath) {}
    default void replay(QuPathGUI qupath, ActionLogger logger) {}

    /**
     * Validates a .qtlicense JWT token.
     * Returns a decoded {@link LicenseInfo} if the signature is valid and the token is not expired,
     * or null if the token is invalid.
     */
    /** Returns the Enterprise plugin version string, e.g. "1.0.2". */
    default String getPluginVersion() { return null; }

    default LicenseInfo validateLicense(String token) { return null; }

    /**
     * Returns the LicenseInfo for the currently loaded license, or null if none/invalid.
     * Used by the panel to display the license holder's name.
     */
    default LicenseInfo getActiveLicenseInfo() { return null; }

    /**
     * Returns true if the loaded .qtlicense contains an encrypted signing key.
     */
    default boolean hasEncryptedSigningKey() { return false; }

    /**
     * Shows a passphrase dialog, decrypts the signing key, and stores it in-memory for this session.
     */
    default void promptPassphraseAndDecrypt(javafx.stage.Stage owner) {}

    /**
     * Returns the decrypted ED25519 private key (base64url PKCS8-DER), or null if not yet decrypted.
     */
    default String getDecryptedSigningKey() { return null; }

    /**
     * Builds a .qtcert certificate and appends to chain.jsonl.
     * Returns the path to the written .qtcert, or null if not supported / no key available.
     */
    default Path buildCertificate(ValidationStamp stamp, JsonObject sessionPayload,
                                   JsonObject imageRoot, Path exportDir) { return null; }

    /**
     * Pushes a bundle (.qtrace + .qtcert + chain.jsonl + classifiers) to the user's qtrace.ca workspace.
     * Returns a CompletableFuture resolving to the workspace URL, or null on failure / not supported.
     * Auth: reads the .qtlicense JWT from QTraceConfig and sends it as a bearer token.
     */
    default CompletableFuture<String> pushToWorkspace(
            ValidationStamp stamp, Path certPath, Path chainLogPath, Path qtraceFile,
            Collection<ClassifierRecord> classifiers) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Builds the compact digest that would be sent to the report endpoint, as a
     * pretty-printed JSON string. Split from {@link #sendReportDigest} so the Core
     * can show the user exactly what leaves their machine (audit/transparency)
     * before anything is transmitted. Returns null on failure / not supported.
     */
    default String buildReportDigest(Path qtraceFile) { return null; }

    /**
     * POSTs a previously-built digest to the qtrace.ca portal, which calls an LLM
     * and returns a Markdown activity report written in the given language code.
     * Auth: reads the .qtlicense JWT from QTraceConfig and sends it as a bearer
     * token. Returns a CompletableFuture resolving to the Markdown report, or null
     * on failure / not supported.
     */
    default CompletableFuture<String> sendReportDigest(String digestJson, String lang) {
        return CompletableFuture.completedFuture(null);
    }
}
