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
 * A proof a module joins to a stamp when it checked what its {@link StampSection} asked, e.g. the
 * security module's second-factor attestation signed by qtrace.ca. Core does not read it: it hands
 * it to Compliance with the stamp, which keeps it in the chain of custody.
 *
 * @param module the module's Qtrace-Module name ("security")
 * @param kind   what it proves ("second-factor")
 * @param value  the proof itself, as the module gives it (a JWT)
 */
public record StampEvidence(String module, String kind, String value) {}
