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

import java.util.Collection;

public interface QTraceExtensionHandler {

    /** Lowercase keyword identifying this extension, e.g. "instanseg". */
    String getExtensionName();

    String getInstallUrl();

    Compat getCompat();

    /** Groovy code injected once at script preamble. Return "" if none. */
    String buildPreambleGroovy();

    /** Transform a step fragment. Default: identity. */
    default String rewriteStepGroovy(String fragment) { return fragment; }

    /**
     * Returns true if this handler should inject its preamble given the set of
     * extensions recorded in the .qtrace file.
     */
    default boolean needsPreamble(Collection<String> usedExtensions) {
        String kw = getExtensionName().toLowerCase();
        return usedExtensions.stream()
            .anyMatch(n -> n.toLowerCase().contains(kw) || kw.contains(n.toLowerCase()))
            && !buildPreambleGroovy().isBlank();
    }

    /** Pure filesystem check — no QuPath API. Returns true by default. */
    default boolean isPrerequisiteMet(String hint) { return true; }

    /** Same check, but also given the prerequisite's original recorded path. Default: ignores it. */
    default boolean isPrerequisiteMet(String hint, String originalPath) { return isPrerequisiteMet(hint); }

    /** Tri-state result of a model presence check: exact match, a usable-but-different version, or absent. */
    enum ModelStatus { PRESENT, VERSION_MISMATCH, MISSING }

    /**
     * Same information as {@link #isPrerequisiteMet(String, String)}, but distinguishes "found the
     * exact version" from "found a different version" instead of collapsing both into a boolean.
     * Default: derived from {@link #isPrerequisiteMet(String, String)}, so handlers that don't
     * override this only ever report PRESENT or MISSING.
     */
    default ModelStatus getModelStatus(String hint, String originalPath) {
        return isPrerequisiteMet(hint, originalPath) ? ModelStatus.PRESENT : ModelStatus.MISSING;
    }

    /**
     * Directory/version name of the model actually resolved on this machine (display only), or
     * null if none was found or the handler doesn't track this. Lets the pre-flight show the
     * source vs. locally-installed version side by side on a VERSION_MISMATCH.
     */
    default String findLocalModelVersion(String hint, String originalPath) { return null; }

    /** Action-specific hint shown in the pre-flight tooltip. "" if none. */
    default String getCaveat() { return ""; }
}
