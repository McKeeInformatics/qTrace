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

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Converts between ED25519 SPKI-DER public keys and did:key identifiers.
 *
 * did:key format for Ed25519:
 *   "did:key:z" + base58btc( [0xed, 0x01] + raw32bytes )
 *
 * SPKI-DER for Ed25519 is 44 bytes: 12-byte header + 32-byte raw key.
 */
public final class DidKey {

    private static final String BASE58_ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private static final byte[] SPKI_HEADER = {
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private DidKey() {}

    public static String fromSpkiDer(String spkiDerBase64url) {
        byte[] spki = java.util.Base64.getUrlDecoder().decode(spkiDerBase64url);
        byte[] raw = Arrays.copyOfRange(spki, 12, 44);
        byte[] multicodec = new byte[34];
        multicodec[0] = (byte) 0xed;
        multicodec[1] = 0x01;
        System.arraycopy(raw, 0, multicodec, 2, 32);
        return "did:key:z" + encodeBase58(multicodec);
    }

    public static byte[] toSpkiDer(String didKey) {
        String encoded = didKey.substring("did:key:z".length());
        byte[] multicodec = decodeBase58(encoded);
        byte[] raw = Arrays.copyOfRange(multicodec, 2, 34);
        byte[] spki = new byte[44];
        System.arraycopy(SPKI_HEADER, 0, spki, 0, 12);
        System.arraycopy(raw, 0, spki, 12, 32);
        return spki;
    }

    private static String encodeBase58(byte[] input) {
        BigInteger num = new BigInteger(1, input);
        BigInteger base = BigInteger.valueOf(58);
        StringBuilder sb = new StringBuilder();
        while (num.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = num.divideAndRemainder(base);
            num = divRem[0];
            sb.append(BASE58_ALPHABET.charAt(divRem[1].intValue()));
        }
        for (byte b : input) {
            if (b != 0) break;
            sb.append(BASE58_ALPHABET.charAt(0));
        }
        return sb.reverse().toString();
    }

    private static byte[] decodeBase58(String input) {
        BigInteger num = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(58);
        for (char c : input.toCharArray()) {
            int digit = BASE58_ALPHABET.indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("Invalid base58 character: " + c);
            num = num.multiply(base).add(BigInteger.valueOf(digit));
        }
        byte[] decoded = num.toByteArray();
        int leadingZeros = 0;
        for (char c : input.toCharArray()) {
            if (c != BASE58_ALPHABET.charAt(0)) break;
            leadingZeros++;
        }
        int stripSignByte = (decoded.length > 1 && decoded[0] == 0) ? 1 : 0;
        byte[] result = new byte[leadingZeros + decoded.length - stripSignByte];
        System.arraycopy(decoded, stripSignByte, result, leadingZeros, decoded.length - stripSignByte);
        return result;
    }
}
