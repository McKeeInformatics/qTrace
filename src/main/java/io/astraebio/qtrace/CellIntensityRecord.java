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
