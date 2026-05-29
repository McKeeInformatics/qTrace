package io.astraebio.qtrace;

import java.time.Instant;

/**
 * Immutable record of one expert validation event.
 * Stored by QTraceController and serialised to JSON in Phase 6.
 */
public record ValidationStamp(
    String  validator,
    Instant timestamp,
    String  scope,
    String  confidence,
    String  notes,
    String  gitHash,
    String  imageHash,
    String  classifierFidelity   // ClassifierFidelity.name() — HIGH / DEGRADED / COMPROMISED
) {}
