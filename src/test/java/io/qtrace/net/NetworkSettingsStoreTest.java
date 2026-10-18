package io.qtrace.net;

import com.google.gson.Gson;
import io.qtrace.QTraceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Settings › Network on disk: config.json for the settings, an owner-only file for the password. */
class NetworkSettingsStoreTest {

    @TempDir Path dir;
    private final Gson gson = new Gson();

    @Test
    void aNewConfig_usesTheComputersProxyAndStore() {
        QTraceConfig cfg = gson.fromJson("{}", QTraceConfig.class);
        assertEquals(NetworkSettings.defaults(), NetworkSettingsStore.load(cfg, dir));
    }

    @Test
    void thePasswordNeverReachesConfigJson_andItsFileIsOwnerOnly() throws Exception {
        QTraceConfig cfg = gson.fromJson("{}", QTraceConfig.class);
        var s = new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, "proxy.chu.fr", 3128,
            "alice", "s3cret-pw", false, null);
        NetworkSettingsStore.store(s, cfg, dir);

        String json = gson.toJson(cfg);
        assertFalse(json.contains("s3cret-pw"), json);
        assertTrue(json.contains("proxy.chu.fr") && json.contains("alice"));
        Path secret = dir.resolve(NetworkSettingsStore.PASSWORD_FILE);
        assertEquals("s3cret-pw", Files.readString(secret));
        assumeTrue(secret.getFileSystem().supportedFileAttributeViews().contains("posix"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secret)));

        QTraceConfig reread = gson.fromJson(json, QTraceConfig.class);
        assertEquals(s, NetworkSettingsStore.load(reread, dir));
        assertFalse(s.toString().contains("s3cret-pw"), "never in a log line");
    }

    @Test
    void withoutAUserName_thePasswordFileIsRemoved() throws Exception {
        QTraceConfig cfg = gson.fromJson("{}", QTraceConfig.class);
        NetworkSettingsStore.store(new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, "p", 1, "a", "pw", true, null), cfg, dir);
        assertTrue(Files.exists(dir.resolve(NetworkSettingsStore.PASSWORD_FILE)));
        NetworkSettingsStore.store(new NetworkSettings(NetworkSettings.ProxyMode.MANUAL, "p", 1, null, "pw", true, null), cfg, dir);
        assertFalse(Files.exists(dir.resolve(NetworkSettingsStore.PASSWORD_FILE)));
        assertNull(NetworkSettingsStore.load(cfg, dir).proxyPassword());
    }

    @Test
    void theSecretFileIsTightenedAtStartLikeTheOtherSecrets() {
        assertTrue(io.qtrace.SecretFiles.NAMES.contains(NetworkSettingsStore.PASSWORD_FILE));
    }

    @Test
    void theInstitutionCertificateIsCopied_andAnythingElseIsRefused() throws Exception {
        Path src = Files.createDirectories(dir.resolve("usb")).resolve("hospital.cer");
        try (var in = getClass().getResourceAsStream("/io/qtrace/net/test-root.pem")) {
            assertNotNull(in, "fixture");
            Files.write(src, in.readAllBytes());
        }
        Path copy = NetworkSettingsStore.importInstitutionCa(src, dir);
        assertEquals(dir.resolve(NetworkSettingsStore.CA_FILE), copy);
        Files.delete(src);
        assertEquals(1, TrustStores.readCertificates(copy).size(), "usable once the USB key is gone");

        Path junk = dir.resolve("junk.pem");
        Files.writeString(junk, "-----BEGIN CERTIFICATE-----\nnope\n-----END CERTIFICATE-----\n");
        assertThrows(java.io.IOException.class, () -> NetworkSettingsStore.importInstitutionCa(junk, dir));
    }

    @Test
    void proxyModeParsing_isLenient() {
        assertEquals(NetworkSettings.ProxyMode.SYSTEM, NetworkSettings.ProxyMode.parse(null));
        assertEquals(NetworkSettings.ProxyMode.SYSTEM, NetworkSettings.ProxyMode.parse("weird"));
        assertEquals(NetworkSettings.ProxyMode.NONE, NetworkSettings.ProxyMode.parse("none"));
        assertEquals(NetworkSettings.ProxyMode.MANUAL, NetworkSettings.ProxyMode.parse(" Manual "));
    }
}
