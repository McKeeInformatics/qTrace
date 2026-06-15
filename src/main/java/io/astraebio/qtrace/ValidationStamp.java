package io.astraebio.qtrace;

import io.astraebio.qtrace.chain.CanonicalJson;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable record of one expert validation event.
 * Stored by QTraceController and serialised to JSON in Phase 6.
 *
 * Phase 19 compliance fields:
 *   signature      — ED25519 signature of canonicalPayload() (base64url); null = unsigned
 *   validatorKeyPub — ED25519 public key of the signer (base64url, SPKI-DER); null = non-certified
 *
 * Phase 20 compliance fields (M0 chain-of-custody):
 *   qpdataSha256   — SHA-256 of the .qpdata file at stamp time; null = no project open
 *
 * canonicalPayload() now produces RFC 8785 (JCS) canonical JSON covering all fields
 * required by 21 CFR §11.50 (including signing_meaning) and the chain-of-custody spec.
 */
public record ValidationStamp(
    String  validator,
    Instant timestamp,
    String  scope,
    String  confidence,
    String  notes,
    String  gitHash,
    String  imageHash,
    String  qpdataSha256,       // SHA-256 of .qpdata at stamp time — binds cert to data state
    String  classifierFidelity, // ClassifierFidelity.name() — HIGH / DEGRADED / COMPROMISED
    int     statusIndex,        // 0=To Begin, 1=In Progress, 2=Finished
    String  statusLabel,        // "0-To Begin" / "1-In Progress" / "2-Finished"
    String  signature,          // ED25519 sig of canonicalPayload() (base64url); null = unsigned
    String  validatorKeyPub     // ED25519 public key (base64url, SPKI-DER); null = non-certified
) {
    /**
     * 21 CFR §11.50(a) — the meaning of the signature, included inside the signed bytes
     * so a validator cannot later claim their signature meant something different.
     */
    public static final String SIGNING_MEANING =
        "I, the named validator, attest that the workflow and qpdata identified by this " +
        "certificate produce results suitable for the stated scope.";

    /**
     * RFC 8785 canonical JSON payload that is signed.
     *
     * All keys sorted lexicographically (Unicode code-unit order, which equals
     * Java TreeMap natural order for ASCII keys). Null field values are rendered
     * as JSON null so the canonical form is deterministic regardless of whether
     * optional fields are present.
     *
     * Key names use snake_case to match the .qtcert JSON schema.
     */
    public String canonicalPayload() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("confidence",      nvl(confidence));
        fields.put("git_hash",        nvl(gitHash));
        fields.put("image_sha256",    nvl(imageHash));
        fields.put("qpdata_sha256",   qpdataSha256);    // may be null → JSON null
        fields.put("scope",           nvl(scope));
        fields.put("signing_meaning", SIGNING_MEANING);
        fields.put("status",          nvl(statusLabel));
        fields.put("timestamp",       timestamp != null ? timestamp.toString() : "");
        fields.put("validator",       nvl(validator));
        fields.put("validator_key_pub", nvl(validatorKeyPub));
        return CanonicalJson.of(fields);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
