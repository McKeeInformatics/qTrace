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

package io.astraebio.qtrace.chain;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Generates Universally Unique Lexicographically Sortable Identifiers (ULIDs).
 *
 * Format: 26 Crockford base32 characters
 *   - 10 chars: 48-bit millisecond timestamp
 *   - 16 chars: 80-bit cryptographically random component
 *
 * Lexicographic sort order matches chronological order.
 */
public final class Ulid {

    private static final char[] CROCKFORD =
        "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final SecureRandom RNG = new SecureRandom();

    private Ulid() {}

    public static String generate() {
        long tsMillis = Instant.now().toEpochMilli();
        byte[] random = new byte[10];
        RNG.nextBytes(random);

        char[] ulid = new char[26];

        // Encode 48-bit timestamp into first 10 chars (5 bits each)
        ulid[0]  = CROCKFORD[(int) ((tsMillis >>> 45) & 0x1F)];
        ulid[1]  = CROCKFORD[(int) ((tsMillis >>> 40) & 0x1F)];
        ulid[2]  = CROCKFORD[(int) ((tsMillis >>> 35) & 0x1F)];
        ulid[3]  = CROCKFORD[(int) ((tsMillis >>> 30) & 0x1F)];
        ulid[4]  = CROCKFORD[(int) ((tsMillis >>> 25) & 0x1F)];
        ulid[5]  = CROCKFORD[(int) ((tsMillis >>> 20) & 0x1F)];
        ulid[6]  = CROCKFORD[(int) ((tsMillis >>> 15) & 0x1F)];
        ulid[7]  = CROCKFORD[(int) ((tsMillis >>> 10) & 0x1F)];
        ulid[8]  = CROCKFORD[(int) ((tsMillis >>>  5) & 0x1F)];
        ulid[9]  = CROCKFORD[(int) ( tsMillis         & 0x1F)];

        // Encode 80-bit random into last 16 chars (5 bits each from 10 bytes = 80 bits)
        long hi = 0;
        for (int i = 0; i < 5; i++) hi = (hi << 8) | (random[i] & 0xFFL);
        long lo = 0;
        for (int i = 5; i < 10; i++) lo = (lo << 8) | (random[i] & 0xFFL);

        ulid[10] = CROCKFORD[(int) ((hi >>> 35) & 0x1F)];
        ulid[11] = CROCKFORD[(int) ((hi >>> 30) & 0x1F)];
        ulid[12] = CROCKFORD[(int) ((hi >>> 25) & 0x1F)];
        ulid[13] = CROCKFORD[(int) ((hi >>> 20) & 0x1F)];
        ulid[14] = CROCKFORD[(int) ((hi >>> 15) & 0x1F)];
        ulid[15] = CROCKFORD[(int) ((hi >>> 10) & 0x1F)];
        ulid[16] = CROCKFORD[(int) ((hi >>>  5) & 0x1F)];
        ulid[17] = CROCKFORD[(int) ( hi         & 0x1F)];
        ulid[18] = CROCKFORD[(int) ((lo >>> 35) & 0x1F)];
        ulid[19] = CROCKFORD[(int) ((lo >>> 30) & 0x1F)];
        ulid[20] = CROCKFORD[(int) ((lo >>> 25) & 0x1F)];
        ulid[21] = CROCKFORD[(int) ((lo >>> 20) & 0x1F)];
        ulid[22] = CROCKFORD[(int) ((lo >>> 15) & 0x1F)];
        ulid[23] = CROCKFORD[(int) ((lo >>> 10) & 0x1F)];
        ulid[24] = CROCKFORD[(int) ((lo >>>  5) & 0x1F)];
        ulid[25] = CROCKFORD[(int) ( lo         & 0x1F)];

        return new String(ulid);
    }
}
