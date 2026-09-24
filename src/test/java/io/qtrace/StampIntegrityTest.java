package io.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StampIntegrityTest {

    private static final String QPDATA = "a".repeat(64);

    private JsonObject root;

    @BeforeEach
    void signedTrace() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pub = Base64.getUrlEncoder().withoutPadding().encodeToString(kp.getPublic().getEncoded());

        JsonObject val = new JsonObject();
        val.addProperty("validator", "Jane Doe");
        val.addProperty("scope", "Full Workflow");
        val.addProperty("confidence", "High");
        val.addProperty("timestamp", "2026-09-13T03:08:17Z");
        val.addProperty("imageHash", "b".repeat(64));
        val.addProperty("qpdata_sha256", QPDATA);
        val.addProperty("statusLabel", "1-In Progress");
        val.addProperty("validatorKeyPub", pub);

        String payload = StampIntegrity.canonicalPayload("Jane Doe", "Full Workflow", "High", "1-In Progress",
            "b".repeat(64), QPDATA, "2026-09-13T03:08:17Z", pub);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(payload.getBytes(StandardCharsets.UTF_8));
        val.addProperty("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign()));

        JsonObject session = new JsonObject();
        session.add("validation", val);
        JsonArray sessions = new JsonArray();
        sessions.add(session);
        root = new JsonObject();
        root.add("sessions", sessions);
        root.addProperty("status", "1-In Progress");
    }

    private JsonObject validation() {
        return root.getAsJsonArray("sessions").get(0).getAsJsonObject().getAsJsonObject("validation");
    }

    @Test
    void intactStampWithUnchangedDataIsOk() {
        assertEquals(StampIntegrity.State.OK, StampIntegrity.check(root, QPDATA));
    }

    @Test
    void editedStatusInvalidatesTheSignature() {
        validation().addProperty("statusLabel", "2-Finished");
        assertEquals(StampIntegrity.State.SIGNATURE_INVALID, StampIntegrity.check(root, QPDATA));
    }

    @Test
    void qpdataDifferentFromTheStampIsDataChanged() {
        assertEquals(StampIntegrity.State.DATA_CHANGED, StampIntegrity.check(root, "c".repeat(64)));
    }

    @Test
    void signatureFailureWinsOverDataChange() {
        validation().addProperty("validator", "Mallory");
        assertEquals(StampIntegrity.State.SIGNATURE_INVALID, StampIntegrity.check(root, "c".repeat(64)));
    }

    @Test
    void unknownCurrentQpdataIsNotReportedAsChanged() {
        assertEquals(StampIntegrity.State.OK, StampIntegrity.check(root, null));
    }

    @Test
    void unsignedStampIsUnsigned() {
        validation().remove("signature");
        assertEquals(StampIntegrity.State.UNSIGNED, StampIntegrity.check(root, QPDATA));
    }

    @Test
    void traceWithoutValidationHasNoStamp() {
        root.getAsJsonArray("sessions").get(0).getAsJsonObject().remove("validation");
        assertEquals(StampIntegrity.State.NO_STAMP, StampIntegrity.check(root, QPDATA));
    }

    // ── unstamped sessions (live autosave) ──────────────────────────────────

    private static JsonObject unstamped() {
        JsonObject s = new JsonObject();
        s.add("validation", com.google.gson.JsonNull.INSTANCE);
        s.addProperty("validation_state", "unstamped");
        return s;
    }

    @Test
    void unstampedSessionAfterTheStampDoesNotHideIt() {
        root.getAsJsonArray("sessions").add(unstamped());
        assertEquals(StampIntegrity.State.OK, StampIntegrity.check(root, QPDATA));
        validation().addProperty("validator", "Mallory");
        assertEquals(StampIntegrity.State.SIGNATURE_INVALID, StampIntegrity.check(root, QPDATA));
    }

    @Test
    void countsUnstampedSessionsSinceTheLastStamp() {
        assertEquals(0, StampIntegrity.unstampedSinceLastStamp(root));
        root.getAsJsonArray("sessions").add(unstamped());
        root.getAsJsonArray("sessions").add(unstamped());
        assertEquals(2, StampIntegrity.unstampedSinceLastStamp(root));
    }

    @Test
    void stampedStateFollowsTheFieldAndFallsBackToTheValidationBlock() {
        assertTrue(StampIntegrity.isStamped(session()), "legacy: validation block present");
        assertFalse(StampIntegrity.isStamped(unstamped()));
        JsonObject legacyUnstamped = new JsonObject();
        legacyUnstamped.add("validation", com.google.gson.JsonNull.INSTANCE);
        assertFalse(StampIntegrity.isStamped(legacyUnstamped));
        assertFalse(StampIntegrity.isStamped(new JsonObject()));
    }

    // ── with the certificate payload ────────────────────────────────────────

    private JsonObject session() {
        return root.getAsJsonArray("sessions").get(0).getAsJsonObject();
    }

    @Test
    void unsignedFieldEditedAfterTheStampIsTraceEdited() {
        JsonObject payload = session().deepCopy();
        session().addProperty("steps_captured", 99);   // not covered by the signature
        assertEquals(StampIntegrity.State.OK, StampIntegrity.check(root, QPDATA));
        assertEquals(StampIntegrity.State.TRACE_EDITED,
            StampIntegrity.check(root, QPDATA, payload, null, null));
    }

    @Test
    void satelliteFileChangedAfterTheStampIsFilesChanged(@TempDir Path dir) throws Exception {
        Path thumb = Files.writeString(dir.resolve("t.jpg"), "pixels");
        JsonObject ref = new JsonObject();
        ref.addProperty("type", "thumbnail");
        ref.addProperty("filename", "t.jpg");
        ref.addProperty("size_bytes", 6);
        ref.addProperty("sha256", io.qtrace.chain.Hashing.sha256Hex(thumb));
        JsonArray files = new JsonArray();
        files.add(ref);
        session().add("external_files", files);
        JsonObject payload = session().deepCopy();
        assertEquals(StampIntegrity.State.OK, StampIntegrity.check(root, QPDATA, payload, dir, dir));
        Files.writeString(thumb, "PIXELS");
        assertEquals(StampIntegrity.State.FILES_CHANGED, StampIntegrity.check(root, QPDATA, payload, dir, dir));
    }

    @Test
    void signatureFailureWinsOverTraceEdits() {
        JsonObject payload = session().deepCopy();
        validation().addProperty("statusLabel", "2-Finished");
        assertEquals(StampIntegrity.State.SIGNATURE_INVALID,
            StampIntegrity.check(root, QPDATA, payload, null, null));
    }

    @Test
    void findsTheCertificatePayloadOfTheStampedSession(@TempDir Path dir) throws Exception {
        session().addProperty("session_id", "s-42");
        validation().addProperty("case_id", "proj/p.qpproj");
        Path certs = Files.createDirectories(dir.resolve("case_proj_p.qpproj").resolve("certs"));
        Files.writeString(certs.resolve("qtc_X.qtcert"),
            "{\"certificate_id\":\"qtc_X\",\"qtrace_payload\":{\"session_id\":\"s-42\"}}");
        assertEquals("s-42", StampIntegrity.findCertPayload(root, dir).get("session_id").getAsString());
    }

    @Test
    void v11CertificateReferenceIsTheSignedSessionText(@TempDir Path dir) throws Exception {
        session().addProperty("session_id", "s-42");
        validation().addProperty("case_id", "proj/p.qpproj");
        Path certs = Files.createDirectories(dir.resolve("case_proj_p.qpproj").resolve("certs"));
        // Readable copy edited to hide a change; the signed text still says what was stamped.
        Files.writeString(certs.resolve("qtc_X.qtcert"), "{\"qtcert_version\":\"1.1\","
            + "\"qtrace_payload\":{\"session_id\":\"s-42\",\"steps_captured\":99},"
            + "\"qtrace_payload_json\":\"{\\\"session_id\\\":\\\"s-42\\\",\\\"steps_captured\\\":10}\"}");
        JsonObject ref = StampIntegrity.findCertPayload(root, dir);
        assertEquals(10, ref.get("steps_captured").getAsInt());
    }

    @Test
    void referencedCertificateDeletedIsCertificateInvalid(@TempDir Path dir) throws Exception {
        validation().addProperty("case_id", "proj/p.qpproj");
        JsonObject ref = new JsonObject();
        ref.addProperty("type", "cert");
        ref.addProperty("filename", "qtc_GONE.qtcert");
        ref.addProperty("sha256", "0".repeat(64));
        JsonArray files = new JsonArray();
        files.add(ref);
        session().add("external_files", files);
        // No certificate payload can be found either — the deletion itself must raise the alert.
        assertEquals(StampIntegrity.State.CERTIFICATE_INVALID, StampIntegrity.check(root, QPDATA, null, dir, dir));
    }
}
