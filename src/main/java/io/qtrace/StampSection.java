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

import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.Node;

/**
 * A block a qTrace module adds to the Validate &amp; Stamp dialog, just above the Stamp button
 * (e.g. the authenticator code of the security module). Registered through
 * {@link StampSections}; Core knows nothing about what it asks.
 */
public interface StampSection {

    /** The block itself, in the dialog's dark palette. Built on the FX thread. */
    Node node();

    /** The Stamp button stays disabled while this is false. */
    ObservableBooleanValue ready();
}
