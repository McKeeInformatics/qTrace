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

package io.qtrace.onboarding;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * "Sign in to qtrace.ca" — client side of the portal's device authorization flow
 * (docs/architecture/loader.md § 17, web/app/api/device/*). No JavaFX: unit-tested.
 *
 * start() gets a secret deviceCode and a userCode to show; the user approves the userCode in
 * the browser; awaitLicense() polls until the portal hands over the .qtlicense envelope, once.
 */
public final class DeviceFlowClient {

    public record Start(String deviceCode, String userCode, String verificationUrl, int interval, int expiresIn) {}

    public enum Outcome { EXPIRED, DENIED, CANCELLED, ERROR }

    public static final class DeviceFlowException extends Exception {
        private final Outcome outcome;
        DeviceFlowException(Outcome outcome, String message) { super(message); this.outcome = outcome; }
        public Outcome outcome() { return outcome; }
    }

    @FunctionalInterface
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final String server;
    private final Sleeper sleeper;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    public DeviceFlowClient(String server, Sleeper sleeper) {
        this.server = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
        this.sleeper = sleeper;
    }

    /** Starts a sign-in; {@code invite} (the invitation code typed in QuPath) rides along, or null. */
    public Start start(String invite) throws IOException, InterruptedException {
        String body = invite == null || invite.isBlank() ? "{}"
            : "{\"invite\":" + new com.google.gson.JsonPrimitive(invite) + "}";
        HttpResponse<String> r = post("/api/device/start", body);
        if (r.statusCode() != 200) throw new IOException("qtrace.ca answered HTTP " + r.statusCode());
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        return new Start(o.get("deviceCode").getAsString(), o.get("userCode").getAsString(),
            o.get("verificationUrl").getAsString(), o.get("interval").getAsInt(), o.get("expiresIn").getAsInt());
    }

    /**
     * Polls every {@code interval} seconds (doubled after a "slow_down") until approval, and
     * returns the .qtlicense envelope as JSON text. Gives up after {@code expiresIn} seconds of
     * polling, when the user cancels, or when the portal answers expired / denied.
     */
    public String awaitLicense(Start s, BooleanSupplier cancelled) throws DeviceFlowException {
        long intervalMs = s.interval() * 1000L;
        long budgetMs = s.expiresIn() * 1000L;
        String body = "{\"deviceCode\":" + new com.google.gson.JsonPrimitive(s.deviceCode()) + "}";
        try {
            while (budgetMs > 0) {
                if (cancelled.getAsBoolean()) throw new DeviceFlowException(Outcome.CANCELLED, "Cancelled");
                sleeper.sleep(intervalMs);
                budgetMs -= intervalMs;
                if (cancelled.getAsBoolean()) throw new DeviceFlowException(Outcome.CANCELLED, "Cancelled");

                HttpResponse<String> r;
                try {
                    r = post("/api/device/poll", body);
                } catch (IOException e) {
                    continue; // transient network error: keep waiting until the code expires
                }
                String status = statusOf(r.body());
                if (r.statusCode() == 429 || "slow_down".equals(status)) { intervalMs *= 2; continue; }
                switch (status) {
                    case "pending" -> { }
                    case "approved" -> {
                        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                        if (!o.has("license")) throw new DeviceFlowException(Outcome.ERROR, "No license in the answer");
                        return o.get("license").toString();
                    }
                    case "denied" -> throw new DeviceFlowException(Outcome.DENIED, "The request was refused on qtrace.ca");
                    case "expired" -> throw new DeviceFlowException(Outcome.EXPIRED, "The code expired");
                    default -> throw new DeviceFlowException(Outcome.ERROR, "Unexpected answer (HTTP " + r.statusCode() + ")");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceFlowException(Outcome.CANCELLED, "Interrupted");
        }
        throw new DeviceFlowException(Outcome.EXPIRED, "The code expired");
    }

    private static String statusOf(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            return o.has("status") ? o.get("status").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(server + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
