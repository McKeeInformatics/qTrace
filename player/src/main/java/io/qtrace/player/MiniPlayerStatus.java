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

/**
 * Snapshot of the replay for the mini-player's status line — see {@link QTraceMiniPlayerBar}.
 * Built by {@link QTraceReplayPlayer#status()}.
 *
 * @param step      1-based index of the step running / just run (0 = none yet)
 * @param imageIndex 1-based position of the current target within {@code imageCount}
 * @param applied   instructions that ran OK over the whole Play (every image)
 * @param failed    instructions that failed
 * @param skipped   instructions left unchecked or skipped as incompatible
 * @param tracesWritten .qtrace files exported for the targets
 */
record MiniPlayerStatus(Phase phase, String replayName, int stepCount, int step, String stepTitle,
                        int imageIndex, int imageCount, String error,
                        int applied, int failed, int skipped, int tracesWritten) {

    enum Phase { EMPTY, LOADING, LOAD_ERROR, READY, RUNNING, PAUSED, FAILED, DONE }

    String text() {
        return switch (phase) {
            case EMPTY      -> "No replay loaded — 📂 to open a .qtrace file or a qtc_… ID";
            case LOADING    -> "Loading the source .qtrace…";
            case LOAD_ERROR -> "✕ Could not open the replay: " + error;
            case READY      -> "✓ Ready to play — " + replayName + " · " + stepCount + " instructions · ▶ play · ▶│ step by step";
            case RUNNING    -> "⏳ " + where() + " · " + stepTitle + " — running…";
            case PAUSED     -> "⏸ Paused after step " + step + "/" + stepCount + " · ▶ resume";
            case FAILED     -> "❌ Step " + step + "/" + stepCount + " failed: " + error + " — ▶ continue · ⤢ details";
            case DONE       -> done();
        };
    }

    private String where() {
        String stepPart = "Step " + step + "/" + stepCount;
        return imageCount > 1 ? "Image " + imageIndex + "/" + imageCount + " · " + stepPart : stepPart;
    }

    private String done() {
        StringBuilder sb = new StringBuilder("✓ Replay finished");
        if (imageCount > 1) sb.append(" on ").append(imageCount).append(" images");
        sb.append(" — ").append(applied).append('/').append(stepCount * Math.max(1, imageCount))
          .append(" instructions applied");
        StringBuilder details = new StringBuilder();
        if (failed > 0) details.append(failed).append(" failed");
        if (skipped > 0) details.append(details.isEmpty() ? "" : ", ").append(skipped).append(" unchecked");
        if (!details.isEmpty()) sb.append(" (").append(details).append(')');
        if (tracesWritten == 1) sb.append(" · .qtrace written");
        else if (tracesWritten > 1) sb.append(" · ").append(tracesWritten).append(" .qtrace written");
        return sb.toString();
    }
}
