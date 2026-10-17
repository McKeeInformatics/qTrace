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

import java.util.Locale;

/** Sizes shown to the user: "512 B", "12.3 KB", "1.5 MB" (powers of 1024, one decimal under 10). */
public final class ByteSize {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private ByteSize() {}

    public static String format(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        String number = value < 10 ? String.format(Locale.ROOT, "%.1f", value) : String.format(Locale.ROOT, "%.0f", value);
        return number + " " + UNITS[unit];
    }
}
