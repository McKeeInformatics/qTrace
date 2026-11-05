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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reads a file another program is writing in place. QuPath writes a classifier straight into
 * {@code classifiers/object_classifiers/<name>.json} (no temporary file, no rename), so a big RTrees or ANN model
 * can be half on disk when the folder watcher fires: a copy or a sha256 taken then would be of a truncated file.
 */
public final class StableFile {

    private static final long POLL_MS = 50;

    private StableFile() {}

    /**
     * The file's bytes once they have not changed for {@code settleMs} and are one complete JSON object;
     * null when that never happens within {@code timeoutMs} (still being written, not JSON, or gone).
     */
    public static byte[] awaitJson(Path file, long settleMs, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] last = null;
        long stableSince = 0;
        while (System.currentTimeMillis() < deadline) {
            byte[] now = readOrNull(file);
            if (now != null && last != null && Arrays.equals(now, last)) {
                if (System.currentTimeMillis() - stableSince >= settleMs && isJsonObject(now)) return now;
            } else {
                stableSince = System.currentTimeMillis();
                last = now;
            }
            Thread.sleep(POLL_MS);
        }
        return null;
    }

    private static byte[] readOrNull(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            return null;   // being replaced under us: the next poll sees the new file
        }
    }

    private static boolean isJsonObject(byte[] raw) {
        try {
            JsonElement el = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            return el.isJsonObject();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
