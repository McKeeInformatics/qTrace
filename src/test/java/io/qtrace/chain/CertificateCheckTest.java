package io.qtrace.chain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.ProvenanceDiff.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The certificate of an image's latest stamp, checked from QuPath: present, unchanged since the
 * stamp, listed in chain.jsonl, parent intact, signature valid. Uses the cross-language v1.1
 * fixture produced by the real Java CertificateBuilder.
 */
class CertificateCheckTest {

    private static final Path FIXTURE =
        Path.of("..", "tools", "qtrace-verify", "tests", "fixtures", "cert-v1.1.qtcert");

    @TempDir Path exportDir;
    private Path certPath, chainLog;
    private JsonObject root;
    private String certId;

    @BeforeEach
    void stampedCase() throws Exception {
        String text = Files.readString(FIXTURE);
        JsonObject cert = JsonParser.parseString(text).getAsJsonObject();
        certId = cert.get("certificate_id").getAsString();
        Path caseDir = exportDir.resolve("case_proj_p.qpproj");       // case_id "proj/p.qpproj"
        certPath = Files.createDirectories(caseDir.resolve("certs")).resolve(certId + ".qtcert");
        Files.writeString(certPath, text);
        String sha = Hashing.sha256Hex(certPath);
        chainLog = caseDir.resolve("chain.jsonl");
        Files.writeString(chainLog, "{\"chain_position\":1,\"certificate_id\":\"" + certId
            + "\",\"certificate_sha256\":\"" + sha + "\",\"parent_sha256\":null}\n");

        // The .qtrace: its stamped session references the certificate written at stamp time.
        JsonObject session = JsonParser.parseString(cert.get("qtrace_payload_json").getAsString()).getAsJsonObject();
        session.getAsJsonObject("validation").addProperty("case_id", "proj/p.qpproj");
        session.getAsJsonObject("validation").addProperty("validator", "Jane Doe");
        JsonObject ref = new JsonObject();
        ref.addProperty("type", "cert");
        ref.addProperty("filename", certId + ".qtcert");
        ref.addProperty("sha256", sha);
        JsonArray files = new JsonArray();
        files.add(ref);
        session.add("external_files", files);
        JsonArray sessions = new JsonArray();
        sessions.add(session);
        root = new JsonObject();
        root.add("sessions", sessions);
    }

    private List<Finding> check() {
        return CertificateCheck.check(root, exportDir);
    }

    @Test
    void intactCertificateAndChainYieldNoFinding() {
        assertEquals(List.of(), check());
    }

    @Test
    void deletedCertificateIsReported() throws Exception {
        Files.delete(certPath);
        List<Finding> f = check();
        assertEquals(1, f.size());
        assertTrue(f.get(0).current().contains("missing"), f.toString());
    }

    @Test
    void certificateDeletedTogetherWithItsChainLineIsStillReported() throws Exception {
        Files.delete(certPath);
        Files.writeString(chainLog, "");
        assertTrue(check().stream().anyMatch(x -> x.current().contains("missing")), check().toString());
    }

    @Test
    void editedReadableSessionIsReported() throws Exception {
        JsonObject cert = JsonParser.parseString(Files.readString(certPath)).getAsJsonObject();
        cert.getAsJsonObject("qtrace_payload").getAsJsonObject("validation").addProperty("statusLabel", "2-Finished");
        Files.writeString(certPath, cert.toString());
        List<Finding> f = check();
        assertTrue(f.stream().anyMatch(x -> x.subject().contains("session")), f.toString());
        assertTrue(f.stream().anyMatch(x -> x.current().contains("modified")), f.toString());
    }

    @Test
    void forgedCertificateWithMatchingChainLineHasAnInvalidSignature() throws Exception {
        // Attacker rewrites the certificate AND chain.jsonl AND the .qtrace reference: only the
        // Ed25519 signature still tells.
        JsonObject cert = JsonParser.parseString(Files.readString(certPath)).getAsJsonObject();
        cert.getAsJsonObject("validator").addProperty("scope", "Image QC");
        Files.writeString(certPath, cert.toString());
        String sha = Hashing.sha256Hex(certPath);
        Files.writeString(chainLog, "{\"chain_position\":1,\"certificate_id\":\"" + certId
            + "\",\"certificate_sha256\":\"" + sha + "\",\"parent_sha256\":null}\n");
        root.getAsJsonArray("sessions").get(0).getAsJsonObject().getAsJsonArray("external_files")
            .get(0).getAsJsonObject().addProperty("sha256", sha);
        List<Finding> f = check();
        assertEquals(1, f.size(), f.toString());
        assertTrue(f.get(0).subject().contains("signature"), f.toString());
    }

    @Test
    void chainLineRemovedIsReported() throws Exception {
        Files.writeString(chainLog, "");
        List<Finding> f = check();
        assertTrue(f.stream().anyMatch(x -> x.subject().contains("chain.jsonl")), f.toString());
    }

    @Test
    void traceWithoutCertificateReferenceIsNotChecked() {
        root.getAsJsonArray("sessions").get(0).getAsJsonObject().remove("external_files");
        assertEquals(List.of(), check());
    }
}
