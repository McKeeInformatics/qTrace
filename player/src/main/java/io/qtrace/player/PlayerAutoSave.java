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
 */

package io.qtrace.player;

/** When the player saves the image a batch is leaving. No JavaFX: unit-tested. */
final class PlayerAutoSave {

    enum Decision {
        SAVE, OFF, NO_IMAGE, NOT_TARGET, TARGET_FAILED, DISCARD_FAILED, INTERRUPTED
    }

    private PlayerAutoSave() {}

    /**
     * @param enabled      the "Save each image" box
     * @param hasImage     an image is open in the viewer
     * @param onTarget     that image is the target just played (its open did not fail or time out)
     * @param targetFailed a step failed on the target just played: its work is not worth keeping
     * @param interrupted  the batch was paused or stopped, not played to the end
     * @param movingOn     another target opens next (false for the last one, which stays on screen)
     */
    static Decision decide(boolean enabled, boolean hasImage, boolean onTarget,
                           boolean targetFailed, boolean interrupted, boolean movingOn) {
        if (!enabled) return Decision.OFF;
        if (interrupted) return Decision.INTERRUPTED;
        if (!hasImage) return Decision.NO_IMAGE;
        if (!onTarget) return Decision.NOT_TARGET;
        // A failed target's work is dropped when the batch moves on: left unsaved, QuPath would ask
        // "Save changes?" and stop the batch. The last one stays on screen for the user to look at.
        if (targetFailed) return movingOn ? Decision.DISCARD_FAILED : Decision.TARGET_FAILED;
        return Decision.SAVE;
    }
}
