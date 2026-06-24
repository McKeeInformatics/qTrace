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

import com.google.gson.JsonArray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary Merkle tree over a JsonArray of workflow steps.
 * Each leaf is SHA-256( step.toString().getBytes(UTF-8) ).
 * Interior nodes are SHA-256( leftHash || rightHash ) in bytes.
 * Odd-length lists duplicate the last node (standard Bitcoin-style pairing).
 */
public final class MerkleTree {

    private MerkleTree() {}

    public static String merkleRoot(JsonArray steps) {
        if (steps == null || steps.size() == 0)
            return Hashing.sha256Hex(new byte[0]);

        List<byte[]> leaves = new ArrayList<>(steps.size());
        for (var el : steps) {
            byte[] data = el.toString().getBytes(StandardCharsets.UTF_8);
            leaves.add(sha256Bytes(data));
        }

        return bytesToHex(reduce(leaves));
    }

    private static byte[] reduce(List<byte[]> nodes) {
        if (nodes.size() == 1) return nodes.get(0);

        List<byte[]> next = new ArrayList<>((nodes.size() + 1) / 2);
        for (int i = 0; i < nodes.size(); i += 2) {
            byte[] left  = nodes.get(i);
            byte[] right = (i + 1 < nodes.size()) ? nodes.get(i + 1) : left;
            byte[] combined = new byte[64];
            System.arraycopy(left,  0, combined,  0, 32);
            System.arraycopy(right, 0, combined, 32, 32);
            next.add(sha256Bytes(combined));
        }
        return reduce(next);
    }

    private static byte[] sha256Bytes(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
