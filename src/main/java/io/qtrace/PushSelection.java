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

/**
 * What Upload sends for the record the last stamp (or the image's {@code .qtrace}) left:
 * a certified record travels with its certificate and chain of custody; a basic record
 * ({@link BasicRecord}) travels with its qtb_ id and nothing else of the certification.
 * Pure but for the existence check of the chain file: unit-tested.
 */
record PushSelection(Kind kind, Path certPath, Path chainLogPath, String recordId) {

    enum Kind { CERTIFIED, BASIC, NO_CHAIN, NOTHING }

    static PushSelection of(Path certPath, String recordId, Path qtracePath) {
        if (qtracePath == null) return nothing();
        if (certPath != null) {
            // chain.jsonl is at case_<id>/chain.jsonl, cert at case_<id>/certs/<id>.qtcert
            Path chain = certPath.getParent().getParent().resolve("chain.jsonl");
            return Files.exists(chain)
                ? new PushSelection(Kind.CERTIFIED, certPath, chain, null)
                : new PushSelection(Kind.NO_CHAIN, certPath, chain, null);
        }
        if (BasicRecord.isId(recordId)) return new PushSelection(Kind.BASIC, null, null, recordId);
        return nothing();
    }

    private static PushSelection nothing() {
        return new PushSelection(Kind.NOTHING, null, null, null);
    }
}
