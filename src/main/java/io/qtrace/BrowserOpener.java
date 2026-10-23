/*
 * qTrace — QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package io.qtrace;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Opens a URL, a file or a folder with the operating system's default handler.
 *
 * Never use java.awt.Desktop here: initializing AWT inside the JavaFX/GTK process can crash the
 * JVM on Linux. gio / xdg-open / open / explorer.exe don't touch AWT. Public: the provisioning,
 * welcome, player and dashboard modules use it too.
 *
 * Linux prefers {@code gio open} to {@code xdg-open}: outside GNOME/KDE, xdg-utils 1.2 ignores
 * {@code Terminal=true}, so a terminal app set as the default (Neovim for a .log) ran with no
 * window at all and the button seemed to do nothing. gio honours it (xdg-terminal-exec).
 *
 * Windows goes through explorer.exe, never {@code cmd /c start}: Java quotes an argument that
 * holds a space, and {@code start} takes its first quoted argument as the title of a new console
 * window, so a path like {@code C:\Users\Jane Doe\project} opened a command prompt instead of
 * Explorer. cmd also reads {@code &} in a URL as a command separator.
 */
public final class BrowserOpener {

    private BrowserOpener() {}

    /** Opens {@code url} (or a file / folder path) on a background thread; failures are ignored (nothing to recover). */
    public static void open(String url) {
        new Thread(() -> {
            try {
                // Detached from QuPath's pipes: nothing reads them, and a handler that writes to them would block
                new ProcessBuilder(command(System.getProperty("os.name", ""), url, onPath("gio")))
                    .redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            } catch (Exception ignored) {}
        }, "qtrace-browser").start();
    }

    static List<String> command(String osName, String url, boolean hasGio) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return hasGio ? List.of("gio", "open", url) : List.of("xdg-open", url);
        if (os.contains("mac"))   return List.of("open", url);
        return List.of("explorer.exe", url);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator))
            if (!dir.isEmpty() && new File(dir, exe).canExecute()) return true;
        return false;
    }
}
