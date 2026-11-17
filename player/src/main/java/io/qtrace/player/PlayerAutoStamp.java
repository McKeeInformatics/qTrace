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

/**
 * When a Target image(s) batch stamps the image it just played, as Import stamps every image of
 * a project. Only with two or more Targets: a single image is stamped by hand. No JavaFX: unit-tested.
 */
final class PlayerAutoStamp {

    enum Decision {
        STAMP, OFF, SINGLE_TARGET, NOT_TARGET, NO_STEPS, TARGET_FAILED
    }

    private PlayerAutoStamp() {}

    /**
     * @param enabled      the "Stamp each image" box
     * @param targetCount  how many Targets the batch plays
     * @param onTarget     the image on screen is the target just played (its open did not fail or time out)
     * @param hasSteps     qTrace recorded something on it
     * @param targetFailed a step failed on it: a replay that did not go through is never validated
     */
    static Decision decide(boolean enabled, int targetCount, boolean onTarget,
                           boolean hasSteps, boolean targetFailed) {
        if (!enabled) return Decision.OFF;
        if (targetCount < 2) return Decision.SINGLE_TARGET;
        if (!onTarget) return Decision.NOT_TARGET;
        if (!hasSteps) return Decision.NO_STEPS;
        if (targetFailed) return Decision.TARGET_FAILED;
        return Decision.STAMP;
    }
}
