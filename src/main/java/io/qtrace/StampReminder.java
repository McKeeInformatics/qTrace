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

/**
 * The rule behind "\"<image>\" wasn't stamped - stamp it now?" when leaving an image.
 * No JavaFX: unit-tested.
 */
final class StampReminder {

    private StampReminder() {}

    /**
     * @param held           a Player batch of two or more Targets is opening the images itself
     * @param skipped        "Don't ask again this session"
     * @param enabled        Settings: remind me of unstamped images
     * @param leavingAnImage an image was open
     * @param hasNewWork     qTrace captured something new on it
     * @param stamped        it was stamped as it is now ({@link #stampedAsItIs})
     */
    static boolean needed(boolean held, boolean skipped, boolean enabled,
                          boolean leavingAnImage, boolean hasNewWork, boolean stamped) {
        return !held && !skipped && enabled && leavingAnImage && hasNewWork && !stamped;
    }

    /** The last stamp is this image's and nothing changed in the capture since. */
    static boolean stampedAsItIs(String imageHash, String stampImageHash, long changesAtStamp, long changesNow) {
        return imageHash != null && imageHash.equals(stampImageHash)
            && changesAtStamp >= 0 && changesNow == changesAtStamp;
    }
}
