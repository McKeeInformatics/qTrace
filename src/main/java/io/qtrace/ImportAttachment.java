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

/**
 * A file the user attached to an "Import objects from file" event: typically the script (and
 * its config) that generated the imported file. Archived next to the .qtrace like the imported
 * file itself, never executed at replay. Content is cached as bytes at import time for the same
 * reason as {@link ImportedObjectFileRecord#content}.
 */
public class ImportAttachment {

    public final String name;               // original filename, e.g. "train_model.py"
    public final String companionFilename;  // exported name, e.g. "sample.import_1.gen_1.train_model.py"
    public final String sha256;
    public final byte[] content;

    public ImportAttachment(String name, String companionFilename, String sha256, byte[] content) {
        this.name              = name;
        this.companionFilename = companionFilename;
        this.sha256            = sha256;
        this.content           = content;
    }

    /**
     * {@code <imported file's companion name without extension>.gen_<index>.<original name>}, the
     * original name reduced to characters safe as one path segment (the portal refuses the rest).
     */
    public static String companionFilename(String importCompanion, int index, String originalName) {
        int dot = importCompanion.lastIndexOf('.');
        // "img.import_1.geojson" -> "img.import_1"; "img.import_3" (no extension) stays whole.
        String base = importCompanion.matches(".*\\.import_\\d+") ? importCompanion
            : (dot > 0 ? importCompanion.substring(0, dot) : importCompanion);
        String safe = originalName.replaceAll("[^a-zA-Z0-9._-]", "_").replace("..", "_");
        return base + ".gen_" + index + "." + safe;
    }
}
