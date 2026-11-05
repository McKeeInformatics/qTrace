package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** QuPath writes a classifier in place, with no temporary file: a reader must wait for the whole file. */
class StableFileTest {

    @Test
    void aCompleteFileIsReadAtOnce(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.json");
        Files.writeString(f, "{\"a\":1}");
        long t0 = System.currentTimeMillis();
        assertEquals("{\"a\":1}", new String(StableFile.awaitJson(f, 100, 5000), StandardCharsets.UTF_8));
        assertTrue(System.currentTimeMillis() - t0 < 1500, "a stable file costs about one settle delay");
    }

    @Test
    void aFileStillBeingWrittenIsReadWhole(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("slow.json");
        String[] chunks = {"{\"model\":\"", "RTrees-with-a-long-body-", "that-takes-time-to-write", "\"}"};
        Thread writer = new Thread(() -> {
            try (OutputStream out = Files.newOutputStream(f)) {
                for (String c : chunks) { out.write(c.getBytes(StandardCharsets.UTF_8)); out.flush(); Thread.sleep(250); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        writer.start();
        Thread.sleep(50);                                  // the first chunk is on disk: a naive reader would take it
        byte[] raw = StableFile.awaitJson(f, 120, 8000);
        writer.join();
        assertEquals(String.join("", chunks), new String(raw, StandardCharsets.UTF_8));
    }

    @Test
    void aFileThatNeverBecomesCompleteJsonIsGivenUpOn(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("broken.json");
        Files.writeString(f, "{\"a\":");
        assertNull(StableFile.awaitJson(f, 50, 600));
    }

    @Test
    void aFileThatIsNotAnObjectIsNotAClassifier(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("list.json");
        Files.writeString(f, "[1,2]");
        assertNull(StableFile.awaitJson(f, 50, 600));
    }

    @Test
    void aMissingFileGivesNothing(@TempDir Path dir) throws Exception {
        assertNull(StableFile.awaitJson(dir.resolve("gone.json"), 50, 600));
    }
}
