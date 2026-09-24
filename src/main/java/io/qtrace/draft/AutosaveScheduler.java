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

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Decides when the live draft is written — like a document editor's autosave:
 * <ul>
 *   <li>after a quiet period following the last notified change (debounce, 2 s by default,
 *       stretched to 5× the last image-snapshot duration so huge images don't stall QuPath);</li>
 *   <li>every 30 s regardless, as a safety net for changes nobody notified — the flusher is
 *       then asked to write only if the state actually differs;</li>
 *   <li>immediately on {@link #flushNow()} (image switch, QuPath closing).</li>
 * </ul>
 * All writes happen on one thread, so drafts are never written concurrently. The timing
 * logic is in {@link #poll(long)} and driven by an explicit clock, which keeps it testable.
 */
public final class AutosaveScheduler implements AutoCloseable {

    public static final long MAX_DEBOUNCE_MS = 20_000;
    private static final long TICK_MS        = 250;
    private static final long FLUSH_WAIT_MS  = 10_000;

    public enum Status { SAVING, SAVED, FAILED }
    public record Event(Status status, Instant at, String message) {}
    public record FlushResult(boolean wrote, long snapshotMillis) {}

    @FunctionalInterface
    public interface Flusher {
        /** @param changedOnly true for a safety-net pass: write only if the state differs from the last write. */
        FlushResult flush(boolean changedOnly) throws Exception;
    }

    private final Flusher         flusher;
    private final Consumer<Event> listener;
    private final long            baseDebounceMs;
    private final long            safetyNetMs;

    private boolean dirty          = false;
    private long    lastChangeAt   = 0;
    private long    lastFlushAt    = 0;
    private long    retryAt        = -1;
    private long    debounceMs;
    private volatile Event lastEvent;

    private ScheduledExecutorService exec;

    public AutosaveScheduler(Flusher flusher, Consumer<Event> listener,
                             long baseDebounceMs, long safetyNetMs) {
        this.flusher        = flusher;
        this.listener       = listener;
        this.baseDebounceMs = baseDebounceMs;
        this.safetyNetMs    = safetyNetMs;
        this.debounceMs     = baseDebounceMs;
    }

    public Event lastEvent() { return lastEvent; }

    /** Something in the capture changed; write it once things settle. */
    public synchronized void markDirty(long now) {
        dirty        = true;
        lastChangeAt = now;
    }

    public void markDirty() { markDirty(System.currentTimeMillis()); }

    /** One scheduling step. Returns true if a flush ran (successfully or not). */
    public boolean poll(long now) {
        boolean changedOnly;
        synchronized (this) {
            boolean debounced = dirty && now - lastChangeAt >= debounceMs
                                      && (retryAt < 0 || now >= retryAt);
            boolean safety    = now - lastFlushAt >= safetyNetMs;
            if (!debounced && !safety) return false;
            changedOnly = !dirty;
            dirty = false;
        }
        runFlush(now, changedOnly);
        return true;
    }

    /** Writes right now on the calling thread — only for the writer thread and tests. */
    public void flushNowSync(long now) {
        synchronized (this) { dirty = false; }
        runFlush(now, false);
    }

    private void runFlush(long now, boolean changedOnly) {
        if (!changedOnly) emit(Status.SAVING, null);
        try {
            FlushResult r = flusher.flush(changedOnly);
            synchronized (this) {
                lastFlushAt = now;
                retryAt     = -1;
                debounceMs  = Math.min(MAX_DEBOUNCE_MS, Math.max(baseDebounceMs, 5 * r.snapshotMillis()));
            }
            if (r.wrote()) emit(Status.SAVED, null);
        } catch (Exception e) {
            synchronized (this) {
                dirty       = true;           // keep it: retried after another quiet period
                lastFlushAt = now;
                retryAt     = now + debounceMs;
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            emit(Status.FAILED, msg);
        }
    }

    private void emit(Status status, String message) {
        Event e = new Event(status, Instant.now(), message);
        lastEvent = e;
        try { listener.accept(e); } catch (Exception ignored) {}
    }

    // ── Production runner ─────────────────────────────────────────────────────

    public synchronized void start() {
        if (exec != null) return;
        synchronized (this) { lastFlushAt = System.currentTimeMillis(); }
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qtrace-autosave");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(() -> poll(System.currentTimeMillis()),
                                    TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Writes now on the writer thread and waits for it (bounded), e.g. before an image switch. */
    public void flushNow() {
        ScheduledExecutorService e;
        synchronized (this) { e = exec; }
        if (e == null || e.isShutdown()) { flushNowSync(System.currentTimeMillis()); return; }
        try {
            Future<?> f = e.submit(() -> flushNowSync(System.currentTimeMillis()));
            f.get(FLUSH_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            System.err.println("[qTrace] autosave flush: " + ex.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (exec != null) exec.shutdownNow();
        exec = null;
    }
}
