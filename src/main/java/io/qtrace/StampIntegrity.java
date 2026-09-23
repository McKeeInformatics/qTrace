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

package io.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.qtrace.chain.CanonicalJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integrity of the latest stamp of a .qtrace: is its Ed25519 signature still valid, and is
 * the .qpdata on disk still the one it certified? Shared by the Dashboard's 🛡 column and
 * the panel's alert for the open image.
 */
public final class StampIntegrity {

    private StampIntegrity() {}

    public enum State {
        /** No validation block in the latest session. */
        NO_STAMP,
        /** Stamped but not signed (Core without a signing key) — nothing to verify. */
        UNSIGNED,
        /** Signature valid and .qpdata unchanged (or unknown). */
        OK,
        /** The .qtrace stamp fields were edited after signing. */
        SIGNATURE_INVALID,
        /** The stamp is intact, but the .qpdata is no longer the one it certified. */
        DATA_CHANGED
    }

    /**
     * @param currentQpdataSha256 SHA-256 of the image's data.qpdata now, or null if unknown
     *                            (no project, file missing) — then only the signature is checked.
     */
    public static State check(JsonObject root, String currentQpdataSha256) {
        JsonObject session = latestSession(root);
        JsonObject val = session != null ? obj(session, "validation") : null;
        if (val == null || str(val, "validator", "").isEmpty()) return State.NO_STAMP;

        Boolean valid = verifyValidation(val, root, true);
        if (Boolean.FALSE.equals(valid)) return State.SIGNATURE_INVALID;

        String stamped = str(val, "qpdata_sha256", null);
        if (stamped != null && currentQpdataSha256 != null && !stamped.equals(currentQpdataSha256))
            return State.DATA_CHANGED;
        return valid == null ? State.UNSIGNED : State.OK;
    }

    /**
     * Verifies one session's validation block. Returns null when it carries no signature.
     * {@code isLatest}: legacy stamps without statusLabel fall back to the root status, which
     * only describes the latest session.
     */
    public static Boolean verifyValidation(JsonObject val, JsonObject root, boolean isLatest) {
        String sig    = str(val, "signature",       "");
        String pubKey = str(val, "validatorKeyPub", "");
        if (sig.isEmpty() || pubKey.isEmpty()) return null;
        String imgHash = str(val, "imageHash", "");
        if (imgHash.isEmpty()) {
            JsonObject img = obj(root, "image");
            if (img != null) imgHash = str(img, "sha256", "");
        }
        String stLbl = str(val, "statusLabel", "");
        if (stLbl.isEmpty() && isLatest && root != null) stLbl = str(root, "status", "");
        String payload = canonicalPayload(str(val, "validator", ""), str(val, "scope", ""),
            str(val, "confidence", ""), stLbl, imgHash, str(val, "qpdata_sha256", null),
            str(val, "timestamp", ""), pubKey);
        return StampSigner.verify(payload, pubKey, sig);
    }

    /**
     * Reconstructs the RFC 8785 canonical payload for a stamp read from a .qtrace.
     * Mirrors ValidationStamp.canonicalPayload() exactly.
     * qpdataSha256 may be null (stamps created before M0 or without a project).
     */
    public static String canonicalPayload(
            String validator, String scope, String confidence,
            String statusLabel, String imageHash, String qpdataSha256,
            String timestamp, String validatorKeyPub) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("confidence",        confidence     != null ? confidence    : "");
        fields.put("git_hash",          "");            // UI stamps always pass null gitHash
        fields.put("image_sha256",      imageHash      != null ? imageHash     : "");
        fields.put("qpdata_sha256",     qpdataSha256);  // null → JSON null, matches stamp
        fields.put("scope",             scope          != null ? scope         : "");
        fields.put("signing_meaning",   ValidationStamp.SIGNING_MEANING);
        fields.put("status",            statusLabel    != null ? statusLabel   : "");
        fields.put("timestamp",         timestamp      != null ? timestamp     : "");
        fields.put("validator",         validator      != null ? validator     : "");
        fields.put("validator_key_pub", validatorKeyPub != null ? validatorKeyPub : "");
        return CanonicalJson.of(fields);
    }

    static JsonObject latestSession(JsonObject root) {
        if (root == null || !root.has("sessions") || !root.get("sessions").isJsonArray()) return null;
        JsonArray sessions = root.getAsJsonArray("sessions");
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1).getAsJsonObject();
    }

    private static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsString();
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        return o.getAsJsonObject(key);
    }
}
