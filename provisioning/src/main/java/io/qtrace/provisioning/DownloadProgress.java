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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.DoubleConsumer;

/**
 * A GET that tells how far it is. No JavaFX: unit-tested against a local server.
 */
final class DownloadProgress {

    private DownloadProgress() {}

    /**
     * {@code fraction} gets 0..1 as the bytes arrive (only when the server says how long the file is),
     * at most once per percent. Same headers as {@code QTraceUpdater.httpGetBytes}.
     */
    static byte[] get(String url, String bearer, DoubleConsumer fraction) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NORMAL, not ALWAYS: never follow an https → http downgrade with a bearer attached.
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(300)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
            b.header("X-QTrace-Version", io.qtrace.QTraceController.VERSION);
        }
        HttpResponse<InputStream> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(total > 0 && total < Integer.MAX_VALUE ? (int) total : 1 << 16);
        try (InputStream in = resp.body()) {
            byte[] buf = new byte[32 * 1024];
            long read = 0;
            int lastPercent = -1, n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0) {
                    int percent = (int) (read * 100 / total);
                    if (percent != lastPercent) { lastPercent = percent; fraction.accept(Math.min(1.0, (double) read / total)); }
                }
            }
        }
        return out.toByteArray();
    }
}
