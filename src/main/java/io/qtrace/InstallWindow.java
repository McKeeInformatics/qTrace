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

import java.util.function.Supplier;

/**
 * The window where the modules of an accepted update are seen arriving, one progress bar each,
 * then "Quit & Relaunch QuPath now" — as on Getting started's install page. The welcome module
 * brings it; the provisioning module installs and reports to it. Without it, the update ends
 * with the plain "installed" message.
 */
public final class InstallWindow {

    /** What the installer tells the window, from background threads. */
    public interface Progress {
        /**
         * One module, as it downloads: 0 to 1, 1 installed, -1 failed. Every module of the
         * update is first reported at 0, so the window lists them all before the first download.
         *
         * @param title what to call it on screen
         */
        void step(String module, String title, String version, double fraction);

        /** Every module was tried; {@code modules} is how many. The window then offers the restart. */
        void done(int modules);
    }

    private static volatile Supplier<Progress> opener;

    private InstallWindow() {}

    public static void register(Supplier<Progress> opener) {
        if (opener != null) InstallWindow.opener = opener;
    }

    /** Opens the window and gives where to report; null when no module brought one. Call on the FX thread. */
    public static Progress open() {
        Supplier<Progress> o = opener;
        return o == null ? null : o.get();
    }

    static void clear() {
        opener = null;
    }
}
