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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Resolves the version of the JAR a class was loaded from.
 *
 * Never use {@code getPackage().getImplementationVersion()} here: Core and Compliance
 * share package {@code io.qtrace} inside QuPath's single extension classloader, and a
 * Package is defined once, from the manifest of the first JAR that loaded one of its
 * classes — so both modules would report the same (often wrong) version, which made
 * the auto-updater re-offer an already installed Compliance update forever.
 */
public final class JarVersion {

    private JarVersion() {}

    /** Implementation-Version of the JAR {@code anchor} came from; "dev" when unpacked. */
    public static String of(Class<?> anchor) {
        try {
            var src = anchor.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return "dev";
            Path p = Path.of(src.getLocation().toURI());
            if (!Files.isRegularFile(p)) return "dev"; // classes directory (IDE / tests)
            try (JarFile jar = new JarFile(p.toFile(), false)) {
                var mf = jar.getManifest();
                String v = mf != null ? mf.getMainAttributes().getValue("Implementation-Version") : null;
                return v != null && !v.isBlank() ? v : "dev";
            }
        } catch (Exception e) {
            return "dev";
        }
    }
}
