package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** What Upload sends: the certified record (certificate + chain) or the basic one (qtb_ id, no certificate). */
class PushSelectionTest {

    @TempDir Path dir;

    private static final String ID = "qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV";

    private Path certIn(String caseDir, boolean withChain) throws Exception {
        Path certs = Files.createDirectories(dir.resolve(caseDir).resolve("certs"));
        Path cert = Files.writeString(certs.resolve("qtc_x.qtcert"), "{}");
        if (withChain) Files.writeString(dir.resolve(caseDir).resolve("chain.jsonl"), "");
        return cert;
    }

    @Test
    void aCertificateMeansACertifiedRecordWithItsChain() throws Exception {
        Path cert = certIn("case_1", true);
        Path qtrace = dir.resolve("a.qtrace");
        PushSelection s = PushSelection.of(cert, null, qtrace);
        assertEquals(PushSelection.Kind.CERTIFIED, s.kind());
        assertEquals(cert, s.certPath());
        assertEquals(dir.resolve("case_1").resolve("chain.jsonl"), s.chainLogPath());
        assertNull(s.recordId());
    }

    @Test
    void aCertifiedRecordWithoutItsChainCannotBeSent() throws Exception {
        PushSelection s = PushSelection.of(certIn("case_1", false), null, dir.resolve("a.qtrace"));
        assertEquals(PushSelection.Kind.NO_CHAIN, s.kind());
    }

    @Test
    void anIdWithoutCertificateMeansABasicRecordWithNeitherCertificateNorChain() {
        PushSelection s = PushSelection.of(null, ID, dir.resolve("a.qtrace"));
        assertEquals(PushSelection.Kind.BASIC, s.kind());
        assertNull(s.certPath());
        assertNull(s.chainLogPath());
        assertEquals(ID, s.recordId());
    }

    @Test
    void theCertificateWinsWhenBothAreKnown() throws Exception {
        PushSelection s = PushSelection.of(certIn("case_1", true), ID, dir.resolve("a.qtrace"));
        assertEquals(PushSelection.Kind.CERTIFIED, s.kind());
        assertNull(s.recordId(), "a certified record's id is the certificate's");
    }

    @Test
    void nothingToSendBeforeAnExport() throws Exception {
        assertEquals(PushSelection.Kind.NOTHING, PushSelection.of(null, null, dir.resolve("a.qtrace")).kind());
        assertEquals(PushSelection.Kind.NOTHING, PushSelection.of(certIn("case_1", true), null, null).kind());
        assertEquals(PushSelection.Kind.NOTHING, PushSelection.of(null, ID, null).kind());
    }

    @Test
    void aMalformedIdIsNotABasicRecord() {
        assertEquals(PushSelection.Kind.NOTHING, PushSelection.of(null, "qtc_01ARZ3NDEKTSV4RRFFQ69G5FAV", dir.resolve("a.qtrace")).kind());
    }
}
