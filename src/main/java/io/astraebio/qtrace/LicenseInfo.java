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

package io.astraebio.qtrace;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Decoded payload from a validated .qtlicense JWT.
 * validatorKey: ED25519 public key (base64url, SPKI-DER encoded, ~44 chars).
 *               Stable per validator — used for stamp signing and future blockchain anchoring.
 * skEnc/skSalt/skIv: AES-256-GCM encrypted ED25519 private key fields from the JSON envelope.
 *               Null when using the legacy pure-JWT format.
 */
public record LicenseInfo(
    String  clerkUserId,
    String  name,
    String  institution,
    boolean verified,
    Instant issuedAt,
    Instant expiresAt,
    String  validatorKey,  // ED25519 public key base64url, may be empty for older licenses
    String  skEnc,         // AES-GCM encrypted private key (base64url); null = legacy format
    String  skSalt,        // PBKDF2 salt (base64url); null = legacy format
    String  skIv           // AES-GCM IV (base64url); null = legacy format
) {
    public boolean expired() {
        return Instant.now().isAfter(expiresAt);
    }

    /** Human-readable expiry date (ISO local date). */
    public String expiresAtFormatted() {
        return LocalDate.ofInstant(expiresAt, ZoneId.systemDefault()).toString();
    }

    /** Compact fingerprint for display: first 8 + … + last 6 chars. */
    public String validatorKeyShort() {
        if (validatorKey == null || validatorKey.length() < 16) return validatorKey != null ? validatorKey : "";
        return validatorKey.substring(0, 8) + "…" + validatorKey.substring(validatorKey.length() - 6);
    }
}
