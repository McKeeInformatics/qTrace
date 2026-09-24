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

package io.qtrace.draft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.chain.Hashing;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Live drafts: one folder per image under {@code ~/.qTrace/drafts/<imageKey>/}, holding
 * {@code draft.json} (header + {@code logger_state}) and, while the image has unsaved
 * changes, an {@code image-<gen>.qpdata} snapshot of the whole ImageData.
 *
 * Every file is written to a {@code .tmp} sibling then atomically moved into place. The
 * snapshot is written first and {@code draft.json} — which names it — last, so a crash at
 * any point leaves the previous coherent (state, snapshot) pair on disk.
 *
 * Kept out of the export folder on purpose: drafts are machine-local recovery data,
 * potentially large, and must never be synced, pushed or mistaken for a .qtrace.
 */
public final class DraftStore {

    public static final int    DRAFT_FORMAT = 1;
    private static final String DRAFT_FILE  = "draft.json";

    private static final Gson GSON = new GsonBuilder().serializeNulls()
        .serializeSpecialFloatingPointValues().disableHtmlEscaping().create();

    /** Writes the ImageData snapshot bytes (PathIO.writeImageData in production). */
    @FunctionalInterface
    public interface Snapshot { void writeTo(OutputStream out) throws IOException; }

    /** What to do with the image snapshot on this write. */
    public record SnapshotMode(int kind, Snapshot snapshot) {
        static final int KEEP = 0, WRITE = 1, DROP = 2;
        /** Image unchanged since the last snapshot: keep pointing at it. */
        public static SnapshotMode keep()                { return new SnapshotMode(KEEP, null); }
        /** Image changed and unsaved: write a new generation. */
        public static SnapshotMode write(Snapshot s)     { return new SnapshotMode(WRITE, s); }
        /** Image saved to its .qpdata: no snapshot needed any more. */
        public static SnapshotMode drop()                { return new SnapshotMode(DROP, null); }
    }

    /** A draft read back from disk; {@code snapshot} is null when none was needed. */
    public record Draft(String key, JsonObject header, CaptureState state, Path snapshot) {}

    private final Path root;
    private final Predicate<JsonObject> ownerAlive;

    public DraftStore(Path root) { this(root, DraftStore::isOwnerAlive); }

    public DraftStore(Path root, Predicate<JsonObject> ownerAlive) {
        this.root       = root;
        this.ownerAlive = ownerAlive;
    }

    /** Identifies an image independently of its (slow, async) pixel hash. */
    public static String imageKey(String projectPath, String entryId, String serverUri) {
        String id = (projectPath != null && entryId != null)
            ? "project:" + projectPath + "|" + entryId
            : "uri:" + serverUri;
        return Hashing.sha256Hex(id.getBytes(StandardCharsets.UTF_8));
    }

    public Path dir(String key) { return root.resolve(key); }

    /**
     * Persists one coherent (state, snapshot) pair. The header is copied, never mutated;
     * owner, timestamps and snapshot fields are filled in here.
     */
    public synchronized void write(String key, JsonObject header, CaptureState state,
                                   SnapshotMode mode) throws IOException {
        Path dir = dir(key);
        Files.createDirectories(dir);
        JsonObject previous = readHeader(dir).orElse(null);

        JsonObject h = header.deepCopy();
        h.addProperty("draft_format", DRAFT_FORMAT);
        h.addProperty("last_saved_at", Instant.now().toString());
        stampOwner(h);

        String oldSnapshot = previous != null && previous.has("snapshot_file")
            && !previous.get("snapshot_file").isJsonNull()
            ? previous.get("snapshot_file").getAsString() : null;
        long oldGen = previous != null && previous.has("snapshot_gen")
            ? previous.get("snapshot_gen").getAsLong() : 0;

        String newSnapshot = oldSnapshot;
        long   gen         = oldGen;
        if (mode.kind() == SnapshotMode.WRITE) {
            gen = oldGen + 1;
            newSnapshot = "image-" + gen + ".qpdata";
            Path target = dir.resolve(newSnapshot);
            Path tmp    = dir.resolve(newSnapshot + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                mode.snapshot().writeTo(out);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            moveAtomically(tmp, target);
        } else if (mode.kind() == SnapshotMode.DROP) {
            newSnapshot = null;
        }
        if (newSnapshot != null) h.addProperty("snapshot_file", newSnapshot);
        else                     h.remove("snapshot_file");
        h.addProperty("snapshot_gen", gen);

        JsonObject doc = new JsonObject();
        doc.add("header", h);
        doc.add("logger_state", CaptureStateCodec.toJson(state));
        Path tmp = dir.resolve(DRAFT_FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(doc));
        moveAtomically(tmp, dir.resolve(DRAFT_FILE));

        // Only once draft.json points at the new generation is the old one unreachable.
        if (oldSnapshot != null && !oldSnapshot.equals(newSnapshot))
            Files.deleteIfExists(dir.resolve(oldSnapshot));
    }

    /** The draft for this image, or empty if there is none or it can't be understood. */
    public synchronized Optional<Draft> read(String key) {
        Path dir = dir(key);
        Path file = dir.resolve(DRAFT_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonObject doc = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject h   = doc.getAsJsonObject("header");
            CaptureState s = CaptureStateCodec.fromJson(doc.getAsJsonObject("logger_state"));
            Path snap = null;
            if (h.has("snapshot_file") && !h.get("snapshot_file").isJsonNull()) {
                Path p = dir.resolve(h.get("snapshot_file").getAsString());
                if (Files.isRegularFile(p)) snap = p;
            }
            return Optional.of(new Draft(key, h, s, snap));
        } catch (Exception e) {
            System.err.println("[qTrace] draft " + key + " unreadable: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** A draft left behind by a QuPath that is no longer running (crash, kill, power loss). */
    public Optional<Draft> findOrphan(String key) {
        return read(key).filter(d -> !ownerAlive.test(d.header()));
    }

    public synchronized void delete(String key) throws IOException {
        Path dir = dir(key);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /**
     * Moves a draft that could not be restored to {@code _failed/}, so a new draft can take
     * its key without destroying the old work (still there for manual recovery).
     */
    public synchronized Path setAside(String key) throws IOException {
        Path target = root.resolve("_failed").resolve(key + "-" + System.currentTimeMillis());
        Files.createDirectories(target.getParent());
        Files.move(dir(key), target);
        return target;
    }

    // ── Owner process ─────────────────────────────────────────────────────────

    /** Records which process owns the draft; pid_start guards against PID reuse. */
    static void stampOwner(JsonObject h) {
        ProcessHandle me = ProcessHandle.current();
        h.addProperty("pid", me.pid());
        me.info().startInstant().ifPresent(t -> h.addProperty("pid_start", t.toString()));
    }

    /** True while the QuPath that wrote this draft is still running. */
    public static boolean isOwnerAlive(JsonObject h) {
        if (h == null || !h.has("pid")) return false;
        Optional<ProcessHandle> ph = ProcessHandle.of(h.get("pid").getAsLong());
        if (ph.isEmpty() || !ph.get().isAlive()) return false;
        if (!h.has("pid_start")) return true;
        return ph.get().info().startInstant()
            .map(t -> t.toString().equals(h.get("pid_start").getAsString()))
            .orElse(true);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Optional<JsonObject> readHeader(Path dir) {
        Path file = dir.resolve(DRAFT_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(JsonParser.parseString(Files.readString(file))
                .getAsJsonObject().getAsJsonObject("header"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void moveAtomically(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
