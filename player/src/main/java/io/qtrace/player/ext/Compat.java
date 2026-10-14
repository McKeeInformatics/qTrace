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

package io.qtrace.player.ext;

public enum Compat {
    FULL("🟢 Full", "#a6e3a1",
        "Steps are fully captured and embedded in the replay script.\n" +
        "Replay executes identically on a new machine — no manual intervention required."),
    PARTIAL("🟡 Partial", "#f9e2af",
        "Steps are captured and embedded in the replay script.\n" +
        "External resources (model files, servers, credentials) must be present.\n" +
        "qTrace pre-flight dialog flags missing requirements.\n" +
        "One-time setup on the replay machine is needed."),
    NOT_SCRIPTABLE("🔴 Not scriptable", "#f38ba8",
        "Extension is purely interactive — no WorkflowStep events are emitted.\n" +
        "qTrace cannot capture mouse clicks or brush strokes.\n" +
        "Manual re-annotation required on the replay machine.");

    public final String label;
    public final String color;
    public final String tooltip;

    Compat(String label, String color, String tooltip) {
        this.label   = label;
        this.color   = color;
        this.tooltip = tooltip;
    }
}
