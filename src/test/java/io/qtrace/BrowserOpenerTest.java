package io.qtrace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrowserOpenerTest {

    private static final String URL = "https://www.qtrace.ca/portal/device?code=ABCD-EFGH";

    @Test
    void linuxUsesXdgOpen() {
        assertEquals(List.of("xdg-open", URL), BrowserOpener.command("Linux", URL));
    }

    @Test
    void macUsesOpen() {
        assertEquals(List.of("open", URL), BrowserOpener.command("Mac OS X", URL));
    }

    @Test
    void windowsUsesExplorer() {
        assertEquals(List.of("explorer.exe", URL), BrowserOpener.command("Windows 11", URL));
    }

    @Test
    void windowsFolderWithSpacesIsOneArgumentToExplorer() {
        // cmd /c start took the quoted path as a console title: a command prompt opened instead
        String folder = "C:\\Users\\Jane Doe\\My Project\\qTrace";
        assertEquals(List.of("explorer.exe", folder), BrowserOpener.command("Windows 10", folder));
    }
}
