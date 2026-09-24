package io.qtrace.draft;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DraftStoreTest {

    @TempDir Path root;
    private DraftStore store;
    private boolean ownerAlive;

    @BeforeEach
    void setUp() {
        ownerAlive = false;
        store = new DraftStore(root, header -> ownerAlive);
    }

    private static JsonObject header(String sessionId) {
        JsonObject h = new JsonObject();
        h.addProperty("session_id", sessionId);
        h.addProperty("image_name", "slide.svs");
        return h;
    }

    private static CaptureState state(int steps) {
        CaptureState s = new CaptureState();
        for (int i = 0; i < steps; i++) {
            JsonObject st = new JsonObject();
            st.addProperty("command", "step " + i);
            s.capturedSteps.add(st);
        }
        return s;
    }

    private static DraftStore.Snapshot bytes(String content) {
        return out -> out.write(content.getBytes(StandardCharsets.UTF_8));
    }

    private List<Path> files(String key) throws IOException {
        try (Stream<Path> s = Files.list(store.dir(key))) { return s.sorted().toList(); }
    }

    @Test
    void imageKeyIsStableAndDistinguishesImages() {
        String a = DraftStore.imageKey("/p/project.qpproj", "12", "file:/a.svs");
        assertEquals(a, DraftStore.imageKey("/p/project.qpproj", "12", "file:/a.svs"));
        assertNotEquals(a, DraftStore.imageKey("/p/project.qpproj", "13", "file:/a.svs"));
        // Without a project, the server URI identifies the image.
        assertNotEquals(DraftStore.imageKey(null, null, "file:/a.svs"),
                        DraftStore.imageKey(null, null, "file:/b.svs"));
        assertTrue(a.matches("[0-9a-f]{64}"));
    }

    @Test
    void writeThenReadRoundTripsHeaderAndState() throws Exception {
        store.write("k", header("s1"), state(3), DraftStore.SnapshotMode.keep());
        DraftStore.Draft d = store.read("k").orElseThrow();
        assertEquals("s1", d.header().get("session_id").getAsString());
        assertEquals(3, d.state().capturedSteps.size());
        assertNull(d.snapshot());
        assertTrue(d.header().has("last_saved_at"));
    }

    @Test
    void snapshotIsPairedWithTheDraftAndOldGenerationsAreRemoved() throws Exception {
        store.write("k", header("s1"), state(1), DraftStore.SnapshotMode.write(bytes("gen1")));
        store.write("k", header("s1"), state(2), DraftStore.SnapshotMode.write(bytes("gen2")));

        DraftStore.Draft d = store.read("k").orElseThrow();
        assertEquals("gen2", Files.readString(d.snapshot()));
        assertEquals(2, d.state().capturedSteps.size());
        assertEquals(List.of("draft.json", d.snapshot().getFileName().toString()),
                     files("k").stream().map(p -> p.getFileName().toString()).sorted().toList());
    }

    @Test
    void keepRetainsThePreviousSnapshotAndDropRemovesIt() throws Exception {
        store.write("k", header("s1"), state(1), DraftStore.SnapshotMode.write(bytes("gen1")));
        store.write("k", header("s1"), state(2), DraftStore.SnapshotMode.keep());
        assertEquals("gen1", Files.readString(store.read("k").orElseThrow().snapshot()));

        store.write("k", header("s1"), state(3), DraftStore.SnapshotMode.drop());
        assertNull(store.read("k").orElseThrow().snapshot());
        assertEquals(1, files("k").size());
    }

    @Test
    void failedSnapshotLeavesThePreviousPairIntact() throws Exception {
        store.write("k", header("s1"), state(1), DraftStore.SnapshotMode.write(bytes("gen1")));
        DraftStore.Snapshot broken = out -> { out.write(1); throw new IOException("disk full"); };
        assertThrows(IOException.class,
            () -> store.write("k", header("s1"), state(9), DraftStore.SnapshotMode.write(broken)));

        DraftStore.Draft d = store.read("k").orElseThrow();
        assertEquals(1, d.state().capturedSteps.size());
        assertEquals("gen1", Files.readString(d.snapshot()));
        assertTrue(files("k").stream().noneMatch(p -> p.toString().endsWith(".tmp")));
    }

    @Test
    void unreadableDraftIsIgnored() throws Exception {
        Files.createDirectories(store.dir("k"));
        Files.writeString(store.dir("k").resolve("draft.json"), "{ truncated");
        assertTrue(store.read("k").isEmpty());
    }

    @Test
    void orphanOnlyWhenTheOwningProcessIsGone() throws Exception {
        store.write("k", header("s1"), state(1), DraftStore.SnapshotMode.keep());
        ownerAlive = true;
        assertTrue(store.findOrphan("k").isEmpty(), "another live QuPath still owns it");
        ownerAlive = false;
        assertEquals("s1", store.findOrphan("k").orElseThrow().header().get("session_id").getAsString());
        assertTrue(store.findOrphan("missing").isEmpty());
    }

    @Test
    void writeStampsTheOwnerProcess() throws Exception {
        store.write("k", header("s1"), state(0), DraftStore.SnapshotMode.keep());
        JsonObject h = store.read("k").orElseThrow().header();
        assertEquals(ProcessHandle.current().pid(), h.get("pid").getAsLong());
    }

    @Test
    void ownerAliveCheckRecognisesThisProcessAndADeadOne() {
        JsonObject me = new JsonObject();
        DraftStore.stampOwner(me);
        assertTrue(DraftStore.isOwnerAlive(me));
        JsonObject ghost = me.deepCopy();
        ghost.addProperty("pid_start", "1970-01-01T00:00:00Z");  // PID reused by another process
        assertFalse(DraftStore.isOwnerAlive(ghost));
    }

    @Test
    void deleteRemovesTheWholeDraft() throws Exception {
        store.write("k", header("s1"), state(1), DraftStore.SnapshotMode.write(bytes("x")));
        store.delete("k");
        assertFalse(Files.exists(store.dir("k")));
        assertTrue(store.read("k").isEmpty());
    }

    @Test
    void setAsideMovesTheDraftOutOfTheWayWithoutLosingIt() throws Exception {
        store.write("k", header("s1"), state(2), DraftStore.SnapshotMode.write(bytes("gen1")));
        Path kept = store.setAside("k");
        assertTrue(store.read("k").isEmpty(), "a fresh draft can now use the key");
        assertTrue(Files.isRegularFile(kept.resolve("draft.json")));
        assertTrue(kept.startsWith(root.resolve("_failed")));
    }
}
