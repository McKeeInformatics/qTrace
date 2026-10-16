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
package io.qtrace.provisioning;

import io.qtrace.InstallWindow;

/**
 * Core's {@link InstallWindow}, kept in a class of its own: under a Core published before it,
 * only this class fails to load, and the update ends with the plain "installed" message.
 */
final class UpdateWindow {

    /** Where an accepted update reports its modules. */
    interface Window {
        void step(String module, String title, String version, double fraction);
        void done(int modules);
    }

    private UpdateWindow() {}

    /** The welcome module's install window, opened; null without one. Call on the FX thread. */
    static Window open() {
        InstallWindow.Progress p = InstallWindow.open();
        if (p == null) return null;
        return new Window() {
            @Override public void step(String module, String title, String version, double fraction) {
                p.step(module, title, version, fraction);
            }
            @Override public void done(int modules) { p.done(modules); }
        };
    }
}
