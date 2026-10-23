package io.qtrace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void windowsUsesExplorer() {
        assertEquals(List.of("explorer.exe", URL), BrowserOpener.command("Windows 11", URL, false));
    }

    @Test
    void windowsFolderWithSpacesIsOneArgumentToExplorer() {
        // cmd /c start took the quoted path as a console title: a command prompt opened instead
        String folder = "C:\\Users\\Jane Doe\\My Project\\qTrace";
        assertEquals(List.of("explorer.exe", folder), BrowserOpener.command("Windows 10", folder, false));
    }
}
