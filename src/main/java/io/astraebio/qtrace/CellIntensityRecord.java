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

package io.astraebio.qtrace;

import java.time.Instant;

/** One "Set cell intensity classifications → Apply" action for a given measurement. */
public class CellIntensityRecord {
    public final String   measurement;
    public final double[] thresholds;   // 1–3 values: T1+, T2+, T3+
    public final Instant  appliedAt;
    public final String   appliedBy;

    public CellIntensityRecord(String measurement, double[] thresholds,
                                Instant appliedAt, String appliedBy) {
        this.measurement = measurement;
        this.thresholds  = thresholds.clone();
        this.appliedAt   = appliedAt;
        this.appliedBy   = appliedBy;
    }
}
