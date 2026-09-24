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

import com.google.gson.JsonObject;
import io.qtrace.chain.Hashing;
import io.qtrace.draft.AutosaveScheduler;
import io.qtrace.draft.CaptureState;
import io.qtrace.draft.CaptureStateCodec;
import io.qtrace.draft.DraftStore;
import javafx.application.Platform;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.plugins.workflow.WorkflowListener;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The live draft of the image being worked on — qTrace's autosave, Google-Docs style.
 *
 * While an image is open, the capture ({@link ActionLogger#snapshotState()}) and, when the
 * image has unsaved changes, a full {@code .qpdata} snapshot of it are written to
 * {@link QTraceConfig#DRAFTS_DIR} within seconds of every change ({@link AutosaveScheduler}).
 * The draft is removed once its session is committed to the .qtrace — stamped, or unstamped
 * when the image is closed without a stamp. A draft still there when its image is opened
 * again means QuPath died first: {@link QTraceController} then offers to restore it.
 *
 * Thread model: the capture is copied on the JavaFX thread (where it is mutated), the JSON
 * and the snapshot are written on the single autosave thread.
 */
final class DraftManager {

    private static final long DEBOUNCE_MS   = 2_000;
    private static final long SAFETY_NET_MS = 30_000;
    private static final long FX_WAIT_MS    = 3_000;

    private final QuPathGUI    qupath;
    private final ActionLogger logger;
    private final DraftStore   store = new DraftStore(QTraceConfig.DRAFTS_DIR);
    private final AutosaveScheduler scheduler;

    // Current image — touched on the FX thread, read on the writer thread.
    private volatile ImageData<BufferedImage> imageData;
    private volatile String     key;
    private volatile JsonObject header;
    private volatile String     baselineFingerprint;   // state at the last commit (or at open)
    private volatile String     lastWrittenFingerprint;
    private volatile boolean    draftOnDisk;
    private final AtomicBoolean imageDirty = new AtomicBoolean(false);
    // Sessions already in the .qtrace — a write racing a commit must not resurrect their draft.
    private final java.util.Set<String> committedSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final PathObjectHierarchyListener hierarchyListener = e -> onImageChanged();
    private final WorkflowListener            workflowListener  = w -> onImageChanged();

    private record Capture(CaptureState state, boolean imageUnsaved, boolean imageDirty,
                           ImageData<BufferedImage> image, String key, JsonObject header) {}

    DraftManager(QuPathGUI qupath, ActionLogger logger, Consumer<AutosaveScheduler.Event> ui) {
        this.qupath    = qupath;
        this.logger    = logger;
        this.scheduler = new AutosaveScheduler(this::flush, ui, DEBOUNCE_MS, SAFETY_NET_MS);
        scheduler.start();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Image key: project entry when there is one, else the server URI. */
    String keyFor(ImageData<BufferedImage> data) {
        try {
            var project = qupath.getProject();
            if (project != null && project.getPath() != null) {
                var entry = project.getEntry(data);
                if (entry != null)
                    return DraftStore.imageKey(project.getPath().toAbsolutePath().toString(), entry.getID(), null);
            }
        } catch (Exception ignored) {}
        var uris = data.getServer().getURIs();
        String uri = uris.isEmpty() ? data.getServer().getPath() : uris.iterator().next().toString();
        return DraftStore.imageKey(null, null, uri);
    }

    /** A draft left for this image by a QuPath that is no longer running. */
    Optional<DraftStore.Draft> findOrphan(ImageData<BufferedImage> data) {
        if (data == null || !QTraceConfig.get().isAutosaveEnabled()) return Optional.empty();
        return store.findOrphan(keyFor(data));
    }

    /** Starts a fresh draft for an image just attached (nothing to commit yet). FX thread. */
    void begin(ImageData<BufferedImage> data) {
        begin(data, null, false);
    }

    /**
     * Adopts a recovered draft: same session id, and its state counts as uncommitted work
     * even though it is now also the logger's state. Taken over immediately so a second
     * crash doesn't lose it. FX thread.
     */
    void adopt(ImageData<BufferedImage> data, DraftStore.Draft draft) {
        begin(data, draft.header(), true);
        markDirty();
    }

    private void begin(ImageData<BufferedImage> data, JsonObject recoveredHeader, boolean uncommitted) {
        end();
        if (data == null) return;
        imageData = data;
        key       = keyFor(data);
        header    = recoveredHeader != null ? recoveredHeader.deepCopy() : newHeader(data);
        String fp = fingerprint(CaptureStateCodec.toJson(logger.snapshotState()));
        baselineFingerprint    = uncommitted ? "" : fp;
        lastWrittenFingerprint = recoveredHeader != null ? null : fp;
        draftOnDisk            = recoveredHeader != null;
        imageDirty.set(recoveredHeader != null);
        data.getHierarchy().addListener(hierarchyListener);
        data.getHistoryWorkflow().addWorkflowListener(workflowListener);
    }

    /** Stops tracking the current image; its draft stays on disk until committed or discarded. */
    void end() {
        var data = imageData;
        if (data != null) {
            data.getHierarchy().removeListener(hierarchyListener);
            data.getHistoryWorkflow().removeWorkflowListener(workflowListener);
        }
        imageData = null;
        key       = null;
        header    = null;
    }

    private JsonObject newHeader(ImageData<BufferedImage> data) {
        JsonObject h = new JsonObject();
        h.addProperty("session_id", UUID.randomUUID().toString());
        h.addProperty("started_at", Instant.now().toString());
        h.addProperty("qtrace_version", QTraceController.VERSION);
        h.addProperty("image_name", data.getServer().getMetadata().getName());
        var uris = data.getServer().getURIs();
        if (!uris.isEmpty()) h.addProperty("image_uri", uris.iterator().next().toString());
        try {
            var project = qupath.getProject();
            if (project != null && project.getPath() != null) {
                h.addProperty("project_path", project.getPath().toAbsolutePath().toString());
                var entry = project.getEntry(data);
                if (entry != null) h.addProperty("entry_id", entry.getID());
            }
        } catch (Exception ignored) {}
        return h;
    }

    // ── Session bookkeeping ───────────────────────────────────────────────────

    /** Session id the next commit must use — the draft's, so it is one id from first autosave to .qtrace. */
    String sessionId() {
        JsonObject h = header;
        return h != null && h.has("session_id") ? h.get("session_id").getAsString() : null;
    }

    /** True when the capture changed since the image was opened or last committed. FX thread. */
    boolean hasUncommittedChanges() {
        if (imageData == null) return false;
        String fp = fingerprint(CaptureStateCodec.toJson(logger.snapshotState()));
        return !fp.equals(baselineFingerprint);
    }

    /**
     * The draft's session is now in the .qtrace (stamped or not): drop the draft and start a
     * new session id, chained after it by the exporter. FX thread.
     */
    void markCommitted() {
        var data = imageData;
        if (data == null) return;
        String fp = fingerprint(CaptureStateCodec.toJson(logger.snapshotState()));
        String committed = sessionId();
        if (committed != null) committedSessionIds.add(committed);
        baselineFingerprint    = fp;
        lastWrittenFingerprint = fp;
        header = newHeader(data);
        imageDirty.set(false);
        discardFile();
    }

    /** Throws the draft away (Reset capture). FX thread. */
    void discard() {
        markCommitted();
    }

    private void discardFile() {
        String k = key;
        if (k == null) return;
        try { store.delete(k); } catch (Exception e) {
            System.err.println("[qTrace] draft delete: " + e.getMessage());
        }
        draftOnDisk = false;
    }

    /** Moves a draft that failed to restore out of the way (kept under drafts/_failed/). */
    Path setAside(DraftStore.Draft draft) {
        try { return store.setAside(draft.key()); } catch (Exception e) {
            System.err.println("[qTrace] draft set-aside: " + e.getMessage());
            return null;
        }
    }

    void markDirty() {
        if (QTraceConfig.get().isAutosaveEnabled()) scheduler.markDirty();
    }

    /**
     * Best-effort immediate write (JVM shutdown on SIGTERM). From the FX thread it only
     * schedules one: the writer needs the FX thread to copy the capture.
     */
    void flushNow() {
        if (!QTraceConfig.get().isAutosaveEnabled()) return;
        if (Platform.isFxApplicationThread()) scheduler.markDirty(0);
        else                                  scheduler.flushNow();
    }

    void close() {
        end();
        scheduler.close();
    }

    AutosaveScheduler.Event lastEvent() { return scheduler.lastEvent(); }

    private void onImageChanged() {
        imageDirty.set(true);
        markDirty();
    }

    // ── Writer thread ─────────────────────────────────────────────────────────

    private AutosaveScheduler.FlushResult flush(boolean changedOnly) throws Exception {
        if (!QTraceConfig.get().isAutosaveEnabled() || imageData == null)
            return new AutosaveScheduler.FlushResult(false, 0);

        Capture c = onFx(() -> {
            var data = imageData;
            if (data == null || logger.getCurrentImageData() != data) return null;
            return new Capture(logger.snapshotState(), data.isChanged(), imageDirty.getAndSet(false),
                               data, key, header != null ? header.deepCopy() : null);
        });
        if (c == null || c.key() == null || c.header() == null)
            return new AutosaveScheduler.FlushResult(false, 0);

        String fp = fingerprint(CaptureStateCodec.toJson(c.state()));
        boolean needSnapshot = c.imageUnsaved() && (c.imageDirty() || !draftOnDisk);
        boolean untouched    = fp.equals(baselineFingerprint) && !draftOnDisk && !c.imageDirty();
        if (untouched || (changedOnly && fp.equals(lastWrittenFingerprint) && !needSnapshot))
            return new AutosaveScheduler.FlushResult(false, 0);

        long snapshotMillis = 0;
        DraftStore.SnapshotMode mode;
        if (needSnapshot) {
            long t0 = System.nanoTime();
            byte[] bytes = snapshotBytes(c.image());
            snapshotMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            mode = DraftStore.SnapshotMode.write(out -> out.write(bytes));
        } else if (!c.imageUnsaved()) {
            // Saved image: its .qpdata is the snapshot. Remember which one, to spot a later save elsewhere.
            recordQpdata(c.header(), c.image());
            mode = DraftStore.SnapshotMode.drop();
        } else {
            mode = DraftStore.SnapshotMode.keep();
        }
        try {
            store.write(c.key(), c.header(), c.state(), mode);
        } catch (Exception e) {
            if (needSnapshot) imageDirty.set(true);
            throw e;
        }
        // Committed while we were writing: that draft must not outlive its session.
        String writtenId = c.header().has("session_id") ? c.header().get("session_id").getAsString() : null;
        if (writtenId != null && committedSessionIds.contains(writtenId)) {
            try { store.delete(c.key()); } catch (Exception ignored) {}
            return new AutosaveScheduler.FlushResult(false, snapshotMillis);
        }
        if (!c.key().equals(key)) return new AutosaveScheduler.FlushResult(true, snapshotMillis);
        draftOnDisk = true;
        lastWrittenFingerprint = fp;
        if (snapshotMillis > 1_000)
            System.out.println("[qTrace] autosave snapshot took " + snapshotMillis + " ms");
        return new AutosaveScheduler.FlushResult(true, snapshotMillis);
    }

    /**
     * Whole-image snapshot in QuPath's own .qpdata format. Every PathObjectHierarchy mutator
     * is synchronized on the hierarchy, so holding its monitor keeps the objects consistent
     * while they are serialized; the (slower) disk write happens after the lock is released.
     */
    private static byte[] snapshotBytes(ImageData<BufferedImage> data) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);
        synchronized (data.getHierarchy()) {
            PathIO.writeImageData(buf, data);
        }
        return buf.toByteArray();
    }

    private void recordQpdata(JsonObject h, ImageData<BufferedImage> data) {
        Path qpdata = qpdataPath(data);
        if (qpdata == null) return;
        try {
            h.addProperty("qpdata_mtime", Files.getLastModifiedTime(qpdata).toMillis());
            h.addProperty("qpdata_size", Files.size(qpdata));
        } catch (Exception ignored) {}
    }

    /** The image's data.qpdata in the open project, or null (no project, never saved). */
    Path qpdataPath(ImageData<BufferedImage> data) {
        try {
            var project = qupath.getProject();
            if (project == null) return null;
            var entry = project.getEntry(data);
            if (entry == null || entry.getEntryPath() == null) return null;
            Path p = entry.getEntryPath().resolve("data.qpdata");
            return Files.exists(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True when the image's .qpdata was saved again after the draft last looked at it — by
     * another session or QuPath — so restoring the draft would silently overwrite newer work.
     */
    boolean qpdataChangedSince(DraftStore.Draft draft, ImageData<BufferedImage> data) {
        JsonObject h = draft.header();
        if (!h.has("qpdata_mtime")) return false;
        Path qpdata = qpdataPath(data);
        if (qpdata == null) return false;
        try {
            return Files.getLastModifiedTime(qpdata).toMillis() != h.get("qpdata_mtime").getAsLong()
                || Files.size(qpdata) != h.get("qpdata_size").getAsLong();
        } catch (Exception e) {
            return false;
        }
    }

    /** Reads a draft's image snapshot back, against the image's current server. Any thread. */
    static ImageData<BufferedImage> readSnapshot(DraftStore.Draft draft, ImageData<BufferedImage> data)
            throws Exception {
        return PathIO.readImageData(draft.snapshot(), null, data.getServer(), BufferedImage.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Identity of the capture, ignoring the image hash (it lands asynchronously after open). */
    private static String fingerprint(JsonObject stateJson) {
        JsonObject copy = stateJson.deepCopy();
        copy.remove("imageHash");
        return Hashing.sha256Hex(copy.toString().getBytes(StandardCharsets.UTF_8));
    }

    private interface FxCall<T> { T call() throws Exception; }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        if (Platform.isFxApplicationThread()) return call.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { f.complete(call.call()); } catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f.get(FX_WAIT_MS, TimeUnit.MILLISECONDS);
    }
}
