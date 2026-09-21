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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Sends a bug / feature report to the qtrace.ca intake, which creates the GitHub issue
 * server-side. The repository is private and the GitHub token never leaves the server,
 * so this client only carries the .qtlicense JWT (the reporter identity is derived from
 * it server-side — never sent as a field).
 *
 * URL hardcoded for the same reason as the other portal clients: TLS pins the peer to
 * qtrace.ca, a configurable URL would let a tampered config redirect the report.
 */
final class IssueReportClient {

    private IssueReportClient() {}

    // www.qtrace.ca (not the apex): the apex 308-redirects and this client doesn't follow.
    private static final String REPORT_URL = "https://www.qtrace.ca/api/issues/report";

    /** Server-side limits, mirrored here so the dialog can refuse early (web/lib/issue-report.ts). */
    static final int MAX_IMAGES = 5;
    static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;

    record Result(int status, int issueNumber, String serverError) {
        boolean ok() { return status == 200 && issueNumber > 0; }
    }

    static JsonObject buildBody(String title, String description, boolean bug,
                                List<byte[]> pngImages, String appVersion,
                                String qupathVersion, String os) {
        JsonObject body = new JsonObject();
        body.addProperty("title", title);
        body.addProperty("description", description);
        body.addProperty("type", bug ? "bug" : "feature");

        JsonArray images = new JsonArray();
        for (byte[] img : pngImages) images.add(Base64.getEncoder().encodeToString(img));
        body.add("images", images);

        JsonObject env = new JsonObject();
        if (appVersion != null)    env.addProperty("appVersion", appVersion);
        if (qupathVersion != null) env.addProperty("qupathVersion", qupathVersion);
        if (os != null)            env.addProperty("os", os);
        body.add("env", env);
        return body;
    }

    /** i18n key describing an HTTP status for the user. */
    static String messageKey(int status) {
        return switch (status) {
            case 200 -> "report.sent";
            case 401, 403 -> "report.err.license";
            case 429 -> "report.err.rate";
            case 400 -> "report.err.invalid";
            default -> "report.err.server";
        };
    }

    /** Blocking — call off the FX thread. Network failure → status 0. */
    static Result send(String licenseJwt, JsonObject body) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(REPORT_URL))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + licenseJwt)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                    body.toString().getBytes(StandardCharsets.UTF_8)))
                .build();
            HttpResponse<String> resp = client.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int number = 0;
            String err = null;
            try {
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                if (json.has("issueNumber")) number = json.get("issueNumber").getAsInt();
                if (json.has("error")) err = json.get("error").getAsString();
            } catch (Exception ignored) {}
            return new Result(resp.statusCode(), number, err);
        } catch (Exception e) {
            return new Result(0, 0, e.getMessage());
        }
    }
}
