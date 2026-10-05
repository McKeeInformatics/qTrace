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
        /** The stamp's certificate (.qtcert) is missing, altered, badly signed, or out of chain.jsonl. */
        CERTIFICATE_INVALID,
        /** Signature valid, but the stamped session no longer matches its certificate
         *  (unsigned fields such as steps or parameters were edited). */
        TRACE_EDITED,
        /** The stamp is intact, but the .qpdata is no longer the one it certified. */
        DATA_CHANGED,
        /** Stamp and data intact, but a satellite file (thumbnail, GeoJSON, log) changed. */
        FILES_CHANGED;

        /** States the panel and the Dashboard alert on. */
        public boolean isAlert() {
            return this == SIGNATURE_INVALID || this == CERTIFICATE_INVALID || this == TRACE_EDITED
                || this == DATA_CHANGED || this == FILES_CHANGED;
        }
    }

    /**
     * Full check against the stamp's certificate: signature, then the certificate itself
     * ({@link io.qtrace.chain.CertificateCheck}), then the stamped session vs
     * {@code certPayload} (null when there is no certificate — Core), then the .qpdata,
     * then satellite files in {@code exportDir} and the GeoJSON in {@code trainingDir}.
     */
    public static State check(JsonObject root, String currentQpdataSha256, JsonObject certPayload,
                              java.nio.file.Path exportDir, java.nio.file.Path trainingDir) {
        State base = check(root, currentQpdataSha256);
        if (base == State.NO_STAMP || base == State.SIGNATURE_INVALID) return base;
        // Checked even without a payload: a deleted certificate is precisely the case where none is found.
        if (!io.qtrace.chain.CertificateCheck.check(root, exportDir).isEmpty()) return State.CERTIFICATE_INVALID;
        if (certPayload == null) return base;
        JsonObject session = latestValidatedSession(root);
        if (session != null && !ProvenanceDiff.diffSession(certPayload, session, null).isEmpty())
            return State.TRACE_EDITED;
        if (base == State.DATA_CHANGED) return base;
        JsonArray files = certPayload.has("external_files") && certPayload.get("external_files").isJsonArray()
            ? certPayload.getAsJsonArray("external_files") : new JsonArray();
        boolean filesChanged = !ProvenanceDiff.checkFiles(files, exportDir).isEmpty();
        JsonObject ann = obj(certPayload, "annotations");
        if (ann != null)
            filesChanged |= !ProvenanceDiff.checkGeoJson(str(ann, "geojson_file", null),
                str(ann, "geojson_sha256", null), trainingDir).isEmpty();
        return filesChanged ? State.FILES_CHANGED : base;
    }

    /**
     * The .qtcert payload of the latest stamped session, in {@code <exportDir>/case_<caseId>/certs/}
     * — the signed session text for v1.1 certificates (see {@link #signedPayload}).
     */
    public static JsonObject findCertPayload(JsonObject root, java.nio.file.Path exportDir) {
        JsonObject session = latestValidatedSession(root);
        if (session == null || exportDir == null) return null;
        String caseId = str(obj(session, "validation"), "case_id", null);
        String sessionId = str(session, "session_id", null);
        if (caseId == null || sessionId == null) return null;
        java.nio.file.Path certs = exportDir
            .resolve("case_" + caseId.replaceAll("[^a-zA-Z0-9._-]", "_")).resolve("certs");
        if (!java.nio.file.Files.isDirectory(certs)) return null;
        // The session names its certificate (external_files, type "cert"): read that one. Opening
        // every certificate of the case to find it costs seconds per image on a real project.
        String named = certFileName(session);
        if (named != null) {
            JsonObject payload = payloadOf(certs.resolve(named), sessionId);
            if (payload != null) return payload;
        }
        java.nio.file.Path indexed = certIndex(certs).get(sessionId);
        return indexed != null ? payloadOf(indexed, sessionId) : null;
    }

    // ── Which certificate of a case folder belongs to which session ─────────────────────────
    // Learnt by skimming each .qtcert once (no JSON tree: certificates run to tens of MB), and
    // kept while the folder's listing does not change — the Dashboard, the panel and the cohort
    // map ask for every image of a project in a row.

    private record CertIndex(String listing, java.util.Map<String, java.nio.file.Path> bySession) {}

    private static final java.util.Map<java.nio.file.Path, CertIndex> CERT_INDEX =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.Map<String, java.nio.file.Path> certIndex(java.nio.file.Path certs) {
        java.util.List<java.nio.file.Path> files = new java.util.ArrayList<>();
        StringBuilder listing = new StringBuilder();
        try (var list = java.nio.file.Files.list(certs)) {
            for (var p : (Iterable<java.nio.file.Path>) list.filter(f -> f.toString().endsWith(".qtcert")).sorted()::iterator) {
                files.add(p);
                listing.append(p.getFileName()).append('|').append(java.nio.file.Files.size(p)).append('|')
                    .append(java.nio.file.Files.getLastModifiedTime(p).toMillis()).append(';');
            }
        } catch (Exception e) {
            return java.util.Map.of();
        }
        String now = listing.toString();
        CertIndex known = CERT_INDEX.get(certs);
        if (known != null && known.listing().equals(now)) return known.bySession();
        synchronized (CERT_INDEX) {
            known = CERT_INDEX.get(certs);
            if (known != null && known.listing().equals(now)) return known.bySession();
            java.util.Map<String, java.nio.file.Path> bySession = new java.util.HashMap<>();
            for (java.nio.file.Path p : files) {
                String id = sessionIdOf(p);
                if (id != null) bySession.putIfAbsent(id, p);
            }
            CERT_INDEX.put(certs, new CertIndex(now, bySession));
            return bySession;
        }
    }

    /**
     * The session a .qtcert certifies, read without building its JSON tree: the signed text
     * (v1.1, {@code qtrace_payload_json}) when there is one, else the readable copy — the same
     * choice as {@link #signedPayload}. Null when the file is not a certificate.
     */
    static String sessionIdOf(java.nio.file.Path cert) {
        try (com.google.gson.stream.JsonReader r = new com.google.gson.stream.JsonReader(
                java.nio.file.Files.newBufferedReader(cert))) {
            String fromSigned = null, fromCopy = null;
            r.beginObject();
            while (r.hasNext()) {
                String key = r.nextName();
                if ("qtrace_payload_json".equals(key) && r.peek() == com.google.gson.stream.JsonToken.STRING) {
                    try (com.google.gson.stream.JsonReader inner = new com.google.gson.stream.JsonReader(
                            new java.io.StringReader(r.nextString()))) {
                        fromSigned = topLevelSessionId(inner);
                    } catch (Exception ignored) {}
                } else if ("qtrace_payload".equals(key) && r.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                    fromCopy = topLevelSessionId(r);
                } else {
                    r.skipValue();
                }
            }
            return fromSigned != null ? fromSigned : fromCopy;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads one whole object, returning its own {@code session_id} (not a nested one). */
    private static String topLevelSessionId(com.google.gson.stream.JsonReader r) throws java.io.IOException {
        String id = null;
        r.beginObject();
        while (r.hasNext()) {
            if ("session_id".equals(r.nextName()) && r.peek() == com.google.gson.stream.JsonToken.STRING) id = r.nextString();
            else r.skipValue();
        }
        r.endObject();
        return id;
    }

    /** The stamped session a .qtcert carries, when it is the one of {@code sessionId}; else null. */
    private static JsonObject payloadOf(java.nio.file.Path cert, String sessionId) {
        try {
            if (!java.nio.file.Files.isRegularFile(cert)) return null;
            JsonObject payload = signedPayload(com.google.gson.JsonParser.parseString(
                java.nio.file.Files.readString(cert)).getAsJsonObject());
            return payload != null && sessionId.equals(str(payload, "session_id", null)) ? payload : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** File name of the certificate a stamped session points to, or null (older records, Core). */
    private static String certFileName(JsonObject session) {
        if (!session.has("external_files") || !session.get("external_files").isJsonArray()) return null;
        String found = null;
        for (com.google.gson.JsonElement e : session.getAsJsonArray("external_files")) {
            if (!e.isJsonObject() || !"cert".equals(str(e.getAsJsonObject(), "type", null))) continue;
            String name = str(e.getAsJsonObject(), "filename", null);
            // A bare file name only: never a path out of the case's certs folder.
            if (name != null && !name.contains("/") && !name.contains("\\")) found = name;
        }
        return found;
    }

    /**
     * The stamped session of a .qtcert: in v1.1 the signed text (qtrace_payload_json) — covered by
     * the signature, unlike the readable qtrace_payload copy; in v1.0 the readable copy.
     */
    static JsonObject signedPayload(JsonObject cert) {
        if (cert.has("qtrace_payload_json") && cert.get("qtrace_payload_json").isJsonPrimitive()) {
            try {
                return com.google.gson.JsonParser.parseString(cert.get("qtrace_payload_json").getAsString()).getAsJsonObject();
            } catch (Exception ignored) {}
        }
        return obj(cert, "qtrace_payload");
    }

    /** Last session carrying a validation block (sessions exported without a stamp are skipped). */
    public static JsonObject latestValidatedSession(JsonObject root) {
        if (root == null || !root.has("sessions") || !root.get("sessions").isJsonArray()) return null;
        JsonArray sessions = root.getAsJsonArray("sessions");
        for (int i = sessions.size() - 1; i >= 0; i--) {
            JsonObject s = sessions.get(i).getAsJsonObject();
            if (obj(s, "validation") != null) return s;
        }
        return null;
    }

    /**
     * Whether a session was validated by a stamp. {@code validation_state} is written since
     * live autosave; older .qtrace files fall back to the presence of a validation block.
     */
    public static boolean isStamped(JsonObject session) {
        if (session == null) return false;
        String state = str(session, "validation_state", null);
        if (state != null) return "stamped".equals(state);
        return obj(session, "validation") != null;
    }

    /** Sessions recorded (autosaved, never stamped) after the last stamp — or in total if none. */
    public static int unstampedSinceLastStamp(JsonObject root) {
        if (root == null || !root.has("sessions") || !root.get("sessions").isJsonArray()) return 0;
        JsonArray sessions = root.getAsJsonArray("sessions");
        int n = 0;
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (isStamped(sessions.get(i).getAsJsonObject())) break;
            n++;
        }
        return n;
    }

    /**
     * @param currentQpdataSha256 SHA-256 of the image's data.qpdata now, or null if unknown
     *                            (no project, file missing) — then only the signature is checked.
     */
    public static State check(JsonObject root, String currentQpdataSha256) {
        // The latest *stamped* session: unstamped sessions autosaved after it must not hide it.
        JsonObject session = latestValidatedSession(root);
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
