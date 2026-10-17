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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteSizeTest {

    @Test
    void smallSizesAreBytesWithNoDecimal() {
        assertEquals("0 B", ByteSize.format(0));
        assertEquals("999 B", ByteSize.format(999));
    }

    @Test
    void sizesAreScaledByPowersOf1024WithOneDecimalBelowTenUnits() {
        assertEquals("1.0 KB", ByteSize.format(1024));
        assertEquals("12 KB", ByteSize.format(12_595));
        assertEquals("9.9 KB", ByteSize.format(10_138));
        assertEquals("1.5 MB", ByteSize.format(1_572_864));
        assertEquals("2.0 GB", ByteSize.format(2L * 1024 * 1024 * 1024));
    }

    @Test
    void wholeUnitsFromTenUpHaveNoDecimal() {
        assertEquals("120 KB", ByteSize.format(122_880));
        assertEquals("340 MB", ByteSize.format(340L * 1024 * 1024));
    }
}
