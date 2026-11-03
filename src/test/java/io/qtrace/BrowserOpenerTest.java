package io.qtrace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserOpenerTest {

    private static final String URL = "https://www.qtrace.ca/portal/device?code=ABCD-EFGH";

    @Test
    void linuxUsesGioWhenPresent() {
        // xdg-open ran a Terminal=true default (Neovim for a .log) with no window: nothing appeared
        assertEquals(List.of("gio", "open", URL), BrowserOpener.command("Linux", URL, true));
    }

    @Test
    void linuxFallsBackToXdgOpen() {
        assertEquals(List.of("xdg-open", URL), BrowserOpener.command("Linux", URL, false));
    }

    @Test
    void macUsesOpen() {
        assertEquals(List.of("open", URL), BrowserOpener.command("Mac OS X", URL, false));
    }

    @Test
    void windowsOpensAWebAddressInTheDefaultBrowser() {
        // explorer.exe <url> opened a File Explorer window at City of Hope (2026-11-04): QuPath's
        // sign-in page never appeared. The URL protocol handler is ShellExecute, as Desktop.browse.
        assertEquals(List.of("rundll32.exe", "url.dll,FileProtocolHandler", URL),
            BrowserOpener.command("Windows 11", URL, false));
        assertEquals(List.of("rundll32.exe", "url.dll,FileProtocolHandler", "http://localhost:8765/device?code=AB&x=1"),
            BrowserOpener.command("Windows 10", "http://localhost:8765/device?code=AB&x=1", false));
    }

    @Test
    void windowsOpensAFileOrFolderWithExplorer() {
        assertEquals(List.of("explorer.exe", "C:\\Users\\jane\\qTrace"), BrowserOpener.command("Windows 11", "C:\\Users\\jane\\qTrace", false));
    }

    @Test
    void aWebAddressIsHttpOrHttpsOnly() {
        assertTrue(BrowserOpener.isWebAddress("https://www.qtrace.ca/device"));
        assertTrue(BrowserOpener.isWebAddress("HTTP://localhost:8765"));
        assertFalse(BrowserOpener.isWebAddress("C:\\Users\\jane"));
        assertFalse(BrowserOpener.isWebAddress("/home/jane/x.log"));
        assertFalse(BrowserOpener.isWebAddress("file:///C:/x"));
    }

    @Test
    void windowsFolderWithSpacesIsOneArgumentToExplorer() {
        // cmd /c start took the quoted path as a console title: a command prompt opened instead
        String folder = "C:\\Users\\Jane Doe\\My Project\\qTrace";
        assertEquals(List.of("explorer.exe", folder), BrowserOpener.command("Windows 10", folder, false));
    }
}
