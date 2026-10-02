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

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Tells qtrace.ca which workstation this QuPath runs on ({@link SystemInfo#environment}) —
 * sent once per QuPath start, right after the license check that already says which qTrace
 * is running, and shown in BackOffice › user. The account is the license's: the request only
 * carries the .qtlicense JWT.
 *
 * URL hardcoded like the other portal clients: a configurable one would let a tampered
 * config redirect the request.
 */
final class WorkstationClient {

    private WorkstationClient() {}

    // www.qtrace.ca (not the apex): the apex 308-redirects and this client doesn't follow.
    private static final String URL = "https://www.qtrace.ca/api/workstation";

    static JsonObject body(JsonObject env) {
        JsonObject body = new JsonObject();
        body.add("env", env);
        return body;
    }

    /** Blocking — call off the FX thread. Returns the HTTP status, 0 on a network failure. Never throws. */
    static int send(String licenseJwt, JsonObject env) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + licenseJwt)
                .header("Content-Type", "application/json")
                .header("X-QTrace-Version", QTraceController.VERSION)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body(env).toString().getBytes(StandardCharsets.UTF_8)))
                .build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return 0;
        }
    }
}
