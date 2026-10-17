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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Provenance record for one "Import objects from file" event
 * (File > Import objects from file, or a project drag-drop import).
 *
 * Content is cached as bytes at import time — not re-read from
 * {@code sourcePath} later — since that original file may move, be
 * deleted, or the cloud Workspace push may happen in a later session.
 * Created by ActionLogger.recordFileImport(); consumed by QTraceExporter
 * (writes the companion file next to the .qtrace) and WorkspacePusher
 * (uploads it to the cloud bucket).
 */
public class ImportedObjectFileRecord {

    public final String  name;               // original filename, e.g. "regions.geojson"
    public final String  companionFilename;  // exported name, e.g. "sample.import_1.geojson"
    public final String  sourcePath;          // absolute path on the capturing machine — audit only
    public final byte[]  content;
    public final String  sha256;
    public final int     objectCount;
    public final Instant importedAt;
    public final String  importedByUser;
    public final String  context;             // what the file is and where it comes from, written by the user
    private final List<ImportAttachment> attachments; // scripts / configs that generated it; null in an older draft

    /** A file written next to the .qtrace and pushed with it. */
    public record Companion(String filename, byte[] content) {}

    public ImportedObjectFileRecord(String name, String companionFilename, String sourcePath,
                                     byte[] content, String sha256, int objectCount,
                                     Instant importedAt, String importedByUser) {
        this(name, companionFilename, sourcePath, content, sha256, objectCount, importedAt, importedByUser,
            "", List.of());
    }

    public ImportedObjectFileRecord(String name, String companionFilename, String sourcePath,
                                     byte[] content, String sha256, int objectCount,
                                     Instant importedAt, String importedByUser,
                                     String context, List<ImportAttachment> attachments) {
        this.name               = name;
        this.companionFilename  = companionFilename;
        this.sourcePath         = sourcePath;
        this.content            = content;
        this.sha256             = sha256;
        this.objectCount        = objectCount;
        this.importedAt         = importedAt;
        this.importedByUser     = importedByUser;
        this.context            = context == null ? "" : context;
        this.attachments        = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public List<ImportAttachment> attachments() {
        return attachments == null ? List.of() : attachments;
    }

    /** The imported file, then its attachments: everything this event writes and uploads. */
    public List<Companion> companions() {
        List<Companion> all = new ArrayList<>();
        all.add(new Companion(companionFilename, content));
        for (ImportAttachment a : attachments()) all.add(new Companion(a.companionFilename, a.content));
        return all;
    }

    public long totalBytes() {
        long total = content.length;
        for (ImportAttachment a : attachments()) total += a.content.length;
        return total;
    }
}
