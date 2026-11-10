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

package io.qtrace.player;

import java.util.regex.Pattern;

/**
 * One executable unit of a replay run — a deduplicated, rewritten Groovy fragment
 * ready to be evaluated standalone against a shared GroovyShell/Binding.
 *
 * {@code index} matches the "[QTrace] Step N" numbering already emitted by
 * {@link QTraceReplayEngine#generateGroovyScript}, so logs/UI stay consistent
 * with the full-script replay path.
 */
public record ReplayStep(int index, String type, String title, String groovyFragment,
                         boolean validated, String sessionId, boolean skipped,
                         ObjectClassifierFile objectClassifierFile) {

    /**
     * What a creation step stands for: one recorded version of an object classifier, put in the project when the
     * project does not hold that exact file ({@link ObjectClassifierReplay}). {@code needed}: an instruction listed
     * after it runs this version; when none does, the step is listed but has nothing to run.
     */
    public record ObjectClassifierFile(String name, String sha256, boolean needed) {}

    /** An instruction (not an object classifier's creation). */
    public ReplayStep(int index, String type, String title, String groovyFragment,
                      boolean validated, String sessionId, boolean skipped) {
        this(index, type, title, groovyFragment, validated, sessionId, skipped, null);
    }

    /** A step the author left in the replay. */
    public ReplayStep(int index, String type, String title, String groovyFragment,
                      boolean validated, String sessionId) {
        this(index, type, title, groovyFragment, validated, sessionId, false);
    }

    /**
     * A step not tied to validation bookkeeping — treated as validated.
     * {@code validated}: the instruction appears in at least one stamped session of the .qtrace;
     * false means it was only ever recorded in unstamped (autosaved) sessions.
     * {@code sessionId}: session its (last) occurrence comes from, or null.
     * {@code skipped}: the author took the instruction out of the replay ({@code replay_skip}
     * on its last occurrence) — it stays in the trace and is never run.
     */
    public ReplayStep(int index, String type, String title, String groovyFragment) {
        this(index, type, title, groovyFragment, true, null);
    }

    /** Same step renumbered (e.g. after unvalidated steps were left out). */
    public ReplayStep withIndex(int newIndex) {
        return new ReplayStep(newIndex, type, title, groovyFragment, validated, sessionId, skipped, objectClassifierFile);
    }

    /** Whether this step creates an object classifier in the project rather than running an instruction. */
    public boolean createsObjectClassifier() {
        return objectClassifierFile != null;
    }

    private static final Pattern CELL_DETECTION = Pattern.compile(
        "runPlugin\\(['\"][^'\"]*(CellDetection|WatershedCellDetection)");
    private static final Pattern OBJECT_CLASSIFIER = Pattern.compile("runObjectClassifier\\s*\\(");

    /**
     * Lightweight classification — Script / Segmentation / Pixel Classifier / Object Classifier / Cell detection.
     * Plain substring searches first: a fragment can weigh 400 KB (a manual annotation carries its
     * geometry) and a regex over it, once per step and per session, took seconds.
     */
    public static String classify(String fragment) {
        if (fragment == null || fragment.isBlank()) return "Script";
        if (fragment.contains("createAnnotationsFromPixelClassifier")
            || fragment.contains("createDetectionsFromPixelClassifier")
            || fragment.contains("addPixelClassifierMeasurements")) return "Pixel Classifier";
        if (fragment.contains("runObjectClassifier") && OBJECT_CLASSIFIER.matcher(fragment).find()) return "Object Classifier";
        String lower = fragment.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("instanseg") || lower.contains("stardist")) return "Segmentation";
        if (fragment.contains("runPlugin(") && CELL_DETECTION.matcher(fragment).find()) return "Cell detection";
        return "Script";
    }

    /**
     * False for a display-only capture with no Groovy to run — e.g. a "Measurement Map"
     * row built by {@link QTraceReplayEngine#extractMeasurementMapSteps} from a
     * {@code measurement_maps[]} entry, which has no {@code script_fragment} in the .qtrace
     * to begin with — and for an instruction its author took out of the replay
     * ({@link #skipped}). The Player uses this to skip such rows entirely during Play/step
     * execution instead of treating them as merely unchecked.
     */
    public boolean isExecutable() {
        return !skipped && groovyFragment != null && !groovyFragment.isBlank();
    }
}
