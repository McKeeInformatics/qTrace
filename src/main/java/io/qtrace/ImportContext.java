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

import java.io.File;
import java.util.List;

/**
 * What the user declares about an imported file before QuPath reads it: a mandatory free-text
 * context, and the optional script / config files that generated it. See {@link ImportContextDialog}.
 */
public record ImportContext(String context, List<File> generatorFiles) {

    public ImportContext {
        context = context == null ? "" : context.strip();
        generatorFiles = generatorFiles == null ? List.of() : List.copyOf(generatorFiles);
    }

    /** The context is mandatory: an import without it is not recorded as understood. */
    public static boolean isAcceptable(String context) {
        return context != null && !context.isBlank();
    }
}
