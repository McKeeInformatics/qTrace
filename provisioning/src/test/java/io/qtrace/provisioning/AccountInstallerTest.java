package io.qtrace.provisioning;

import io.qtrace.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccountInstallerTest {

    @TempDir Path home;

    @Test
    void writesTheTokenAsAQtaccountFileCoreCanRead() throws Exception {
        Path p = AccountInstaller.write("x.y.z", home);
        assertEquals(home.resolve("qtrace.qtaccount"), p);
        assertEquals("x.y.z", Account.jwt(home));
    }

    @Test
    void createsTheFolderAndTouchesNoLicense() throws Exception {
        Path dir = home.resolve("sub").resolve(".qTrace");
        AccountInstaller.write("x.y.z", dir);
        assertTrue(Files.isRegularFile(dir.resolve("qtrace.qtaccount")));
        assertFalse(Files.exists(dir.resolve("qtrace.qtlicense")));
    }

    @Test
    void refusesAnEmptyToken() {
        assertThrows(IllegalArgumentException.class, () -> AccountInstaller.write(" ", home));
        assertThrows(IllegalArgumentException.class, () -> AccountInstaller.write(null, home));
    }

    @Test
    void keepsAnExistingAccountFileAsBackup() throws Exception {
        AccountInstaller.write("old.old.old", home);
        AccountInstaller.write("new.new.new", home);
        assertEquals("new.new.new", Account.jwt(home));
        assertTrue(Files.readString(home.resolve("qtrace.qtaccount.bak")).contains("old.old.old"));
    }
}
