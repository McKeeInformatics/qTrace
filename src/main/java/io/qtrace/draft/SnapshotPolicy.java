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

/**
 * When the live draft copies the image itself (its .qpdata snapshot — objects and history,
 * never the pixels). The qTrace capture is always saved within seconds; only this copy is
 * throttled, because it rewrites the whole object file each time.
 *
 * <ul>
 *   <li>Small images (last copy under the threshold): copied after every change.</li>
 *   <li>Large images: copied right after a key step (a workflow command such as a detection,
 *       classifier or script; a batch of objects added or removed). Other, manual edits are
 *       copied at most once per interval — or never, with {@code largeKeyStepsOnly}.</li>
 * </ul>
 * AUTO uses the defaults below; CUSTOM takes the user's values from Settings › Autosave.
 */
public record SnapshotPolicy(Mode mode, long largeThresholdBytes, long largeIntervalMs,
                             boolean largeKeyStepsOnly) {

    public enum Mode { AUTO, CUSTOM }

    public static final int DEFAULT_THRESHOLD_MB = 50;
    public static final int DEFAULT_INTERVAL_S   = 60;
    public static final int MIN_THRESHOLD_MB     = 5;
    public static final int MAX_THRESHOLD_MB     = 5_000;
    public static final int MIN_INTERVAL_S       = 10;
    public static final int MAX_INTERVAL_S       = 3_600;
    /** Objects added/removed in one hierarchy event from which it counts as a key step. */
    public static final int BATCH_OBJECTS        = 10;

    private static final long MB = 1024L * 1024L;

    public static SnapshotPolicy auto() {
        return new SnapshotPolicy(Mode.AUTO, DEFAULT_THRESHOLD_MB * MB, DEFAULT_INTERVAL_S * 1000L, false);
    }

    public static SnapshotPolicy custom(int thresholdMb, int intervalS, boolean keyStepsOnly) {
        int mb = Math.max(MIN_THRESHOLD_MB, Math.min(MAX_THRESHOLD_MB, thresholdMb));
        int s  = Math.max(MIN_INTERVAL_S,   Math.min(MAX_INTERVAL_S,   intervalS));
        return new SnapshotPolicy(Mode.CUSTOM, mb * MB, s * 1000L, keyStepsOnly);
    }

    /**
     * @param lastSnapshotBytes size of the previous copy of this image, 0 if none yet
     * @param lastSnapshotAt    when it was written (ms)
     * @param keyPending        a key step happened since the last copy
     * @param minorPending      another change happened since the last copy
     */
    public boolean shouldSnapshot(long lastSnapshotBytes, long lastSnapshotAt, long now,
                                  boolean keyPending, boolean minorPending) {
        if (!keyPending && !minorPending) return false;
        boolean large = lastSnapshotBytes >= largeThresholdBytes;
        if (!large || keyPending) return true;
        if (largeKeyStepsOnly) return false;
        return now - lastSnapshotAt >= largeIntervalMs;
    }

    public int thresholdMb() { return (int) (largeThresholdBytes / MB); }
    public int intervalS()   { return (int) (largeIntervalMs / 1000); }

    /** Workflow commands are key steps — except the manual annotations qTrace records itself. */
    public static boolean isKeyWorkflowStep(String name) {
        return name != null && !name.startsWith("Manual annotation");
    }

    public static boolean isKeyHierarchyChange(int changedObjects) {
        return changedObjects >= BATCH_OBJECTS;
    }
}
