package io.astraebio.qtrace.chain;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * SHA-256 hashing utilities for files and byte arrays.
 * Returns lowercase hex strings (64 chars for SHA-256).
 */
public final class Hashing {

    private Hashing() {}

    /** SHA-256 of a file, streamed in 8 KB chunks. */
    public static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
        }
        return bytesToHex(md.digest());
    }

    /** SHA-256 of a byte array. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return bytesToHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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
