package io.qtrace.net;

import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The certificates qTrace trusts: Java's, plus the chosen ones - never "anything". */
class TrustStoresTest {

    private static X509Certificate testRoot() throws Exception {
        Path tmp = Files.createTempFile("root", ".pem");
        try (InputStream in = TrustStoresTest.class.getResourceAsStream("/io/qtrace/net/test-root.pem")) {
            Files.write(tmp, in.readAllBytes());
        }
        try {
            return TrustStores.readCertificates(tmp).get(0);
        } finally {
            Files.delete(tmp);
        }
    }

    @Test
    void theCompositeAcceptsWhatOneSourceAccepts_andRefusesTheRest() throws Exception {
        X509Certificate root = testRoot();
        var jdk = TrustStores.jdkDefault();
        var composite = new TrustStores.Composite(List.of(jdk, TrustStores.forCertificates(List.of(root))));

        // Java alone refuses the test root; the composite accepts it as an anchor.
        assertThrows(CertificateException.class, () -> jdk.checkServerTrusted(new X509Certificate[]{root}, "ECDHE_ECDSA"));
        composite.checkServerTrusted(new X509Certificate[]{root}, "ECDHE_ECDSA");

        // Every issuer of Java's store is still accepted as before.
        assertTrue(composite.getAcceptedIssuers().length > jdk.getAcceptedIssuers().length);
    }

    @Test
    void withNothingChosen_onlyJavasOwnCertificatesAreUsed() throws Exception {
        var none = new NetworkSettings(NetworkSettings.ProxyMode.SYSTEM, null, 0, null, null, false, null);
        var tm = TrustStores.trustManager(none);
        assertFalse(tm instanceof TrustStores.Composite);
        assertEquals(TrustStores.jdkDefault().getAcceptedIssuers().length, tm.getAcceptedIssuers().length);
    }

    @Test
    void anEmptyCompositeTrustsNothing() {
        var empty = new TrustStores.Composite(List.of());
        assertThrows(CertificateException.class, () -> empty.checkServerTrusted(new X509Certificate[0], "RSA"));
    }

    @Test
    void theIssuerIsNamedTheWayITKnowsIt() {
        assertEquals("FortiGate CA (Fortinet)", TrustStores.shortName(new X500Principal("CN=FortiGate CA, O=Fortinet, C=US")));
        assertEquals("Zscaler Root CA", TrustStores.shortName(new X500Principal("CN=Zscaler Root CA, O=Zscaler Inc., C=US")).replace(" (Zscaler Inc.)", ""));
        assertEquals("Acme, Inc. Root", TrustStores.shortName(new X500Principal("CN=Acme\\, Inc. Root")));
        assertEquals("Only Org", TrustStores.shortName(new X500Principal("O=Only Org, C=FR")));
    }

    @Test
    void theSystemStoreOfThisComputerIsReadable_orSaysWhy() {
        var store = TrustStores.readSystemStore(System.getProperty("os.name"));
        assertTrue(store.available() || store.error() != null, store.name());
    }
}
