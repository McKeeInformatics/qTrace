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

package io.qtrace.chain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.ProvenanceDiff.Finding;
import io.qtrace.ProvenanceDiff.Kind;
import io.qtrace.StampIntegrity;
import io.qtrace.StampSigner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks, from QuPath, the certificate of an image's latest stamp — what only qtrace-verify
 * did so far: the .qtcert the .qtrace references is still there and unchanged, listed in
 * chain.jsonl with the same hash, its parent present and unchanged, and its Ed25519 signature
 * valid (v1.0: 16 fields; v1.1: 17, the signed session text included).
 *
 * The .qtrace keeps the certificate's name and SHA-256 (external_files, type "cert"), so
 * deleting the certificate AND its chain.jsonl line is still caught. Traces without such a
 * reference (Core without Compliance, stamps before certificates) are not checked.
 */
public final class CertificateCheck {

    private CertificateCheck() {}

    public static List<Finding> check(JsonObject root, Path exportDir) {
        List<Finding> out = new ArrayList<>();
        JsonObject session = StampIntegrity.latestValidatedSession(root);
        JsonObject ref = certReference(session);
        if (ref == null || exportDir == null) return out;
        String fileName = str(ref, "filename");
        String caseId = str(obj(session, "validation"), "case_id");
        if (fileName == null || caseId == null) return out;
        String certId = fileName.endsWith(".qtcert") ? fileName.substring(0, fileName.length() - 7) : fileName;
        Path caseDir = exportDir.resolve("case_" + caseId.replaceAll("[^a-zA-Z0-9._-]", "_"));
        Path certPath = caseDir.resolve("certs").resolve(fileName);

        if (!Files.exists(certPath)) {
            out.add(new Finding(Kind.CERTIFICATE, fileName, "present at the stamp", "missing — deleted after the stamp"));
            return out;
        }
        String sha;
        JsonObject cert;
        try {
            sha = Hashing.sha256Hex(certPath);
            cert = JsonParser.parseString(Files.readString(certPath)).getAsJsonObject();
        } catch (Exception e) {
            out.add(new Finding(Kind.CERTIFICATE, fileName, "readable", "unreadable: " + e.getMessage()));
            return out;
        }
        if (!sha.equals(str(ref, "sha256")))
            out.add(new Finding(Kind.CERTIFICATE, fileName, "as written at the stamp", "modified"));

        // chain.jsonl: this certificate listed with its current hash, its parent present.
        Map<String, JsonObject> byId = new TreeMap<>();
        Map<String, String> idByHash = new TreeMap<>();
        try {
            Path log = caseDir.resolve("chain.jsonl");
            if (Files.exists(log))
                for (String line : Files.readAllLines(log)) {
                    if (line.isBlank()) continue;
                    try {
                        JsonObject e = JsonParser.parseString(line).getAsJsonObject();
                        String id = str(e, "certificate_id"), h = str(e, "certificate_sha256");
                        if (id != null) byId.put(id, e);
                        if (id != null && h != null) idByHash.put(h, id);
                    } catch (Exception ignored) {}
                }
        } catch (Exception ignored) {}
        JsonObject entry = byId.get(certId);
        if (entry == null)
            out.add(new Finding(Kind.CERTIFICATE, "chain.jsonl", "lists " + certId, "entry missing"));
        else if (!sha.equals(str(entry, "certificate_sha256")))
            out.add(new Finding(Kind.CERTIFICATE, "chain.jsonl", "hash of " + fileName, "differs from the file"));

        String parentSha = str(obj(cert, "chain"), "parent_certificate_sha256");
        if (parentSha != null) {
            String parentId = idByHash.get(parentSha);
            Path parentPath = parentId != null ? caseDir.resolve("certs").resolve(parentId + ".qtcert") : null;
            boolean intact = false;
            try {
                intact = parentPath != null && Files.exists(parentPath) && parentSha.equals(Hashing.sha256Hex(parentPath));
            } catch (Exception ignored) {}
            if (!intact)
                out.add(new Finding(Kind.CERTIFICATE, "parent certificate", "present, unchanged", "missing or modified"));
        }

        if (payloadMismatch(cert))
            out.add(new Finding(Kind.CERTIFICATE, fileName + " session (qtrace_payload)",
                "identical to the signed text", "edited"));
        if (!signatureValid(cert))
            out.add(new Finding(Kind.CERTIFICATE, fileName + " signature", "valid", "INVALID"));
        return out;
    }

    // ── Signature — mirrors tools/qtrace-verify canonical.py + signature.py ──────────────

    static boolean signatureValid(JsonObject cert) {
        try {
            String did = str(obj(cert, "validator"), "validator_id");
            String sig = str(obj(cert, "signature"), "value");
            if (did == null || sig == null) return false;
            String pub = Base64.getUrlEncoder().withoutPadding().encodeToString(DidKey.toSpkiDer(did));
            return StampSigner.verify(canonicalPayload(cert), pub, sig);
        } catch (Exception e) {
            return false;
        }
    }

    static String canonicalPayload(JsonObject cert) {
        JsonObject chain = obj(cert, "chain"), subject = obj(cert, "subject"), validator = obj(cert, "validator");
        JsonObject payload = obj(cert, "qtrace_payload");
        Map<String, String> f = new TreeMap<>();
        f.put("case_id",                   str(cert, "case_id"));
        f.put("certificate_id",            str(cert, "certificate_id"));
        f.put("chain_position",            str(chain, "chain_position"));
        f.put("confidence",                str(validator, "confidence"));
        f.put("events_count",              str(cert, "events_count"));
        f.put("events_merkle_root",        str(cert, "events_merkle_root"));
        f.put("git_hash",                  str(cert, "git_hash"));
        f.put("image_sha256",              str(subject, "image_sha256"));
        f.put("parent_certificate_sha256", str(chain, "parent_certificate_sha256"));
        f.put("qpdata_sha256",             str(subject, "qpdata_sha256"));
        f.put("scope",                     str(validator, "scope"));
        f.put("signing_meaning",           str(validator, "signing_meaning"));
        f.put("status",                    str(obj(payload, "validation"), "statusLabel"));
        f.put("timestamp",                 str(cert, "issued_at"));
        f.put("validator",                 str(validator, "validator_display_name"));
        f.put("validator_key_pub",         str(validator, "validator_id"));
        if ("1.1".equals(str(cert, "qtcert_version"))) {
            String text = str(cert, "qtrace_payload_json");
            f.put("payload_sha256", text == null ? null : Hashing.sha256Hex(text.getBytes(StandardCharsets.UTF_8)));
        }
        return CanonicalJson.of(f);
    }

    /** v1.1: the readable qtrace_payload must be the parse of the signed qtrace_payload_json. */
    static boolean payloadMismatch(JsonObject cert) {
        if (!"1.1".equals(str(cert, "qtcert_version"))) return false;
        String text = str(cert, "qtrace_payload_json");
        if (text == null) return true;
        try {
            return !JsonParser.parseString(text).equals(cert.get("qtrace_payload"));
        } catch (Exception e) {
            return true;
        }
    }

    private static JsonObject certReference(JsonObject session) {
        if (session == null || !session.has("external_files") || !session.get("external_files").isJsonArray()) return null;
        JsonObject found = null;
        for (JsonElement e : session.getAsJsonArray("external_files"))
            if (e.isJsonObject() && "cert".equals(str(e.getAsJsonObject(), "type"))) found = e.getAsJsonObject();
        return found;
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }
}
