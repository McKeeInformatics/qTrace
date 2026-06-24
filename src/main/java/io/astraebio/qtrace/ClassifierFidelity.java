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
