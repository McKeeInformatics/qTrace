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

package io.qtrace.chain;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Order- and UUID-independent SHA-256 of a set of objects (annotations or detections).
 *
 * Each object becomes one canonical line {@code class|roi_type|cx|cy|area} (1 decimal,
 * Locale.ROOT); lines are sorted and joined with '\n'. A replay that recreates the same
 * objects under new UUIDs yields the same fingerprint, while a reclassification, a move
 * or an added/removed object changes it.
 */
public final class ObjectFingerprint {

    private ObjectFingerprint() {}

    public record Obj(String cls, String roiType, double cx, double cy, double area) {}

    public static String of(Collection<Obj> objects) {
        String joined = objects.stream()
            .map(ObjectFingerprint::line)
            .sorted()
            .collect(Collectors.joining("\n"));
        return Hashing.sha256Hex(joined.getBytes(StandardCharsets.UTF_8));
    }

    static String line(Obj o) {
        return String.format(Locale.ROOT, "%s|%s|%.1f|%.1f|%.1f",
            o.cls(), o.roiType(), o.cx(), o.cy(), o.area());
    }
}
