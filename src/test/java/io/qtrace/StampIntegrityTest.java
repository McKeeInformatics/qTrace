package io.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
