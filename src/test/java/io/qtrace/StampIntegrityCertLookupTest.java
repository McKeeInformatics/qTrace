package io.qtrace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StampIntegrityCertLookupTest {

    @TempDir Path dir;

    private Path cert(String file, String sessionId, String marker) throws Exception {
        Path certs = Files.createDirectories(dir.resolve("case_demo").resolve("certs"));
        Path p = certs.resolve(file);
        Files.writeString(p, "{\"qtcert_version\":\"1.0\",\"qtrace_payload\":{\"session_id\":\"" + sessionId
            + "\",\"marker\":\"" + marker + "\"}}");
        return p;
    }

    private static JsonObject trace(String sessionId, String certFile) {
        String files = certFile == null ? "" : ",\"external_files\":[{\"type\":\"cert\",\"filename\":\"" + certFile + "\"}]";
        return JsonParser.parseString("{\"sessions\":[{\"session_id\":\"" + sessionId
            + "\",\"validation\":{\"validator\":\"Jane\",\"case_id\":\"demo\"}" + files + "}]}").getAsJsonObject();
    }

    @Test
    void theCertificateTheSessionNamesIsTheOneReadWhateverElseTheCaseHolds() throws Exception {
        // Twenty other certificates claim the same session: only the named one counts, and
        // none of the others needs to be opened to know it.
        for (int i = 0; i < 20; i++) cert("qtc_" + (char) ('a' + i) + ".qtcert", "s1", "other");
        cert("qtc_NAMED.qtcert", "s1", "named");

        JsonObject payload = StampIntegrity.findCertPayload(trace("s1", "qtc_NAMED.qtcert"), dir);

        assertNotNull(payload);
        assertEquals("named", payload.get("marker").getAsString());
    }

    @Test
    void aSessionThatNamesNoCertificateStillFindsItsOwnAmongTheCase() throws Exception {
        cert("qtc_A.qtcert", "s0", "another session");
        cert("qtc_B.qtcert", "s1", "mine");
        assertEquals("mine", StampIntegrity.findCertPayload(trace("s1", null), dir).get("marker").getAsString());
    }

    @Test
    void aNamedCertificateOfAnotherSessionIsNotTaken() throws Exception {
        cert("qtc_A.qtcert", "s0", "another session");
        cert("qtc_B.qtcert", "s1", "mine");
        assertEquals("mine", StampIntegrity.findCertPayload(trace("s1", "qtc_A.qtcert"), dir).get("marker").getAsString());
    }

    @Test
    void aNamedCertificateThatIsGoneFallsBackToTheCase() throws Exception {
        cert("qtc_B.qtcert", "s1", "mine");
        assertEquals("mine", StampIntegrity.findCertPayload(trace("s1", "qtc_gone.qtcert"), dir).get("marker").getAsString());
        assertNull(StampIntegrity.findCertPayload(trace("s9", "qtc_gone.qtcert"), dir));
    }

    @Test
    void aCertificateAddedAfterAFirstLookupIsFound() throws Exception {
        cert("qtc_A.qtcert", "s0", "another session");
        assertNull(StampIntegrity.findCertPayload(trace("s1", null), dir));
        cert("qtc_B.qtcert", "s1", "mine");
        assertEquals("mine", StampIntegrity.findCertPayload(trace("s1", null), dir).get("marker").getAsString());
    }

    @Test
    void theSessionOfACertificateIsItsOwnNotANestedOne() throws Exception {
        Path certs = Files.createDirectories(dir.resolve("case_demo").resolve("certs"));
        Path copy = certs.resolve("copy.qtcert");
        Files.writeString(copy, "{\"qtrace_payload\":{\"replayed_from\":{\"session_id\":\"nested\"},\"session_id\":\"own\"}}");
        assertEquals("own", StampIntegrity.sessionIdOf(copy));
        // v1.1: the signed text wins over the readable copy.
        Path signed = certs.resolve("signed.qtcert");
        Files.writeString(signed, "{\"qtrace_payload\":{\"session_id\":\"copy\"},"
            + "\"qtrace_payload_json\":\"{\\\"session_id\\\":\\\"signed\\\"}\"}");
        assertEquals("signed", StampIntegrity.sessionIdOf(signed));
        Path junk = certs.resolve("junk.qtcert");
        Files.writeString(junk, "{ not json");
        assertNull(StampIntegrity.sessionIdOf(junk));
    }
}
