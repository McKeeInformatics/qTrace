package io.qtrace.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashingTest {

    // Known SHA-256 vectors
    private static final String EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String ABC_SHA256 =
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void sha256HexOfBytes() {
        assertEquals(EMPTY_SHA256, Hashing.sha256Hex(new byte[0]));
        assertEquals(ABC_SHA256, Hashing.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sha256HexOfFileMatchesByteArrayHash(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sample.bin");
        byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
        Files.write(f, content);
        assertEquals(ABC_SHA256, Hashing.sha256Hex(f));
    }

    @Test
    void fileLargerThanOneChunkStreamsCorrectly(@TempDir Path dir) throws Exception {
        byte[] big = new byte[20_000]; // > 8 KB chunk size
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i % 251);
        Path f = dir.resolve("big.bin");
        Files.write(f, big);
        assertEquals(Hashing.sha256Hex(big), Hashing.sha256Hex(f));
    }
}
