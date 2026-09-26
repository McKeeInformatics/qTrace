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

import java.util.List;
import java.util.Locale;

/**
 * Opens a URL (or a folder) with the operating system's default handler.
 *
 * Never use java.awt.Desktop here: initializing AWT inside the JavaFX/GTK process can crash the
 * JVM on Linux. xdg-open / open / cmd start don't touch AWT. Public: the onboarding and welcome
 * modules use it too.
 */
public final class BrowserOpener {

    private BrowserOpener() {}

    /** Opens {@code url} on a background thread; failures are ignored (nothing to recover). */
    public static void open(String url) {
        new Thread(() -> {
            try {
                new ProcessBuilder(command(System.getProperty("os.name", ""), url)).start();
            } catch (Exception ignored) {}
        }, "qtrace-browser").start();
    }

    static List<String> command(String osName, String url) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return List.of("xdg-open", url);
        if (os.contains("mac"))   return List.of("open", url);
        return List.of("cmd", "/c", "start", url);
    }
}
