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
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

/**
 * The account token of a basic account (no identity certificate), kept in
 * {@code ~/.qTrace/qtrace.qtaccount} as {@code {"jwt":"…"}}. The workstation never verifies
 * it: the server does, on every request that presents it ({@link QTraceUpdater#bearer()}).
 * What is read from its payload here is for display only.
 *
 * <p>Every method takes the folder, so tests never touch the real one; the variants without
 * a folder read {@code ~/.qTrace}.
 */
public final class Account {

    public static final String FILE = "qtrace.qtaccount";

    /** What the token says about its holder, for display. Missing fields are empty. */
    public record Info(String name, String email, String institution) {}

    private Account() {}

    /** The folder qTrace keeps its files in. */
    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".qTrace");
    }

    // ── From a folder (testable) ─────────────────────────────────────────────────

    /** The token stored in {@code dir}, or null. */
    public static String jwt(Path dir) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(dir.resolve(FILE))).getAsJsonObject();
            if (!o.has("jwt") || !o.get("jwt").isJsonPrimitive()) return null;
            String jwt = o.get("jwt").getAsString().strip();
            return jwt.isEmpty() ? null : jwt;
        } catch (Exception e) {
            return null;
        }
    }

    /** Name, email and institution from the token's payload (not verified); null when it cannot be read. */
    public static Info info(Path dir) {
        JsonObject p = payload(jwt(dir));
        if (p == null) return null;
        return new Info(text(p, "name"), text(p, "email"), text(p, "institution"));
    }

    /** A readable token whose {@code exp} is still ahead of {@code now}. */
    public static boolean signedIn(Path dir, Instant now) {
        JsonObject p = payload(jwt(dir));
        if (p == null || !p.has("exp") || !p.get("exp").isJsonPrimitive()) return false;
        try {
            return p.get("exp").getAsLong() > now.getEpochSecond();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** How the account is named on screen: its name, else its email, else empty. */
    public static String displayName(Info info) {
        if (info == null) return "";
        if (!info.name().isBlank()) return info.name();
        return info.email();
    }

    /**
     * Whether a signed-in account stands for this workstation. A licence always wins (what is
     * presented to qtrace.ca is {@link QTraceUpdater#bearer()}): the account counts only when no
     * licence is configured, so a certified workstation never changes because of a leftover token.
     */
    public static boolean counts(boolean hasLicence, boolean accountSignedIn) {
        return !hasLicence && accountSignedIn;
    }

    // ── From ~/.qTrace ───────────────────────────────────────────────────────────

    public static String jwt() { return jwt(defaultDir()); }

    public static Info info() { return info(defaultDir()); }

    public static boolean signedIn() { return signedIn(defaultDir(), Instant.now()); }

    // ── Token payload ────────────────────────────────────────────────────────────

    private static JsonObject payload(String jwt) {
        if (jwt == null) return null;
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
