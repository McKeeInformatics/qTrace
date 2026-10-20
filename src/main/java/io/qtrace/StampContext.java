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

import java.security.SecureRandom;
import java.util.Base64;

/**
 * What a {@link StampSection} is told when the validator clicks Stamp.
 *
 * @param nonce     drawn for this click: a module binds its proof to it, and the proof is kept
 *                  with the stamp, so a proof is never reused for another stamp
 * @param imageName the image being stamped, as QuPath names it; null when unknown
 * @param imageHash SHA-256 of the image (hex); null while still computing
 */
public record StampContext(String nonce, String imageName, String imageHash) {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 24 random bytes, base64url without padding (32 characters). */
    static String newNonce() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
