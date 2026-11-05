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

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * The upload of a stamped record to the cloud workspace, brought by a module
 * ({@link WorkspacePushes}). Core's Upload button calls it with what the last stamp wrote.
 */
public interface WorkspacePush {

    /**
     * What one upload sends. {@code certPath} and {@code chainLogPath} are the certificate and
     * the chain of custody the stamp produced; the companion files may be absent (null or empty).
     *
     * <p>A <b>basic</b> record (a stamp that produced no certificate) has no certificate and no
     * chain: {@code certPath} and {@code chainLogPath} are null and {@code recordId} is its
     * {@code qtb_…} id. For a certified record {@code recordId} is null (its id is the
     * certificate's).
     *
     * <p>{@code objectClassifiers} are the object classifiers' files the record references (verified against their
     * sha256, see {@link ObjectClassifierFiles#forPush}); they are sent so that a replay of the record opened by its ID
     * uses exactly the recorded classifier.
     *
     * <p>{@code restampedFrom} is the {@code qtb_…} id a <b>certified</b> record declares it
     * certifies (it re-stamps a basic record); null in every other case.
     */
    record Request(ValidationStamp stamp, Path certPath, Path chainLogPath, Path qtraceFile,
                   Collection<ClassifierRecord> classifiers, Path thumbnailPath,
                   Collection<ImportedObjectFileRecord> importedFiles, Path geojsonPath,
                   String recordId, String restampedFrom,
                   Collection<ObjectClassifierFiles.PushFile> objectClassifiers) {

        /** A request without object classifier files: what a caller built before they travelled with the record. */
        public Request(ValidationStamp stamp, Path certPath, Path chainLogPath, Path qtraceFile,
                       Collection<ClassifierRecord> classifiers, Path thumbnailPath,
                       Collection<ImportedObjectFileRecord> importedFiles, Path geojsonPath,
                       String recordId, String restampedFrom) {
            this(stamp, certPath, chainLogPath, qtraceFile, classifiers, thumbnailPath, importedFiles, geojsonPath,
                recordId, restampedFrom, java.util.List.of());
        }
    }

    /**
     * Sends the record, off the FX thread. Resolves to the workspace URL of the record, or to
     * {@code "ERROR:<reason>"} (or null) when nothing was sent.
     */
    CompletableFuture<String> push(Request request);
}
