package io.astraebio.qtrace;

/**
 * Integrity level of the pixel classifier provenance chain.
 * Computed by ActionLogger and embedded in every ValidationStamp.
 */
public enum ClassifierFidelity {

    /** No classifiers used, or all classifiers intact with training data unchanged. */
    HIGH,

    /** Training annotations were modified after the classifier was saved. */
    DEGRADED,

    /** Classifier file SHA-256 changed after capture (external edit or re-save). */
    COMPROMISED;

    /** Returns the worst-case fidelity across two values (ordinal = severity). */
    public static ClassifierFidelity worst(ClassifierFidelity a, ClassifierFidelity b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
