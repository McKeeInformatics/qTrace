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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A replay puts the classifier files it recorded back into the project. Core's folder watcher sees those files
 * appear: without this, it would report them as the user saving a classifier (a false "Object classifier saved",
 * and a naming-policy alert per file). The replay script announces each file - name and sha256 - just before
 * writing it; the watcher then records the file as restored by a replay instead.
 */
public final class ObjectClassifierRestores {

    /** An announcement that no write answers is forgotten after this long. */
    private static final long TTL_MS = 5 * 60_000;

    private static final Map<String, Long> EXPECTED = new ConcurrentHashMap<>();

    private ObjectClassifierRestores() {}

    /** Called by the replay script (Groovy) before it writes a classifier file. */
    public static void expect(String name, String sha256) {
        expect(name, sha256, System.currentTimeMillis());
    }

    /** True once for a file the replay announced: it is a restore, not a save. */
    public static boolean consume(String name, String sha256) {
        return consume(name, sha256, System.currentTimeMillis());
    }

    static void expect(String name, String sha256, long now) {
        EXPECTED.put(key(name, sha256), now);
    }

    static boolean consume(String name, String sha256, long now) {
        Long at = EXPECTED.remove(key(name, sha256));
        return at != null && now - at <= TTL_MS;
    }

    static void clear() {
        EXPECTED.clear();
    }

    private static String key(String name, String sha256) {
        return name + "\u0000" + sha256;
    }
}
