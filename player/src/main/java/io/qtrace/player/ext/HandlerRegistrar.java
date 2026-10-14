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

public class HandlerRegistrar {

    public static void registerAll(ExtensionHandlerRegistry registry) {
        // InstanSeg — dedicated handler with preamble + step rewriting
        registry.register(new InstanSegHandler());

        // All other known extensions — metadata + per-extension action caveat
        registry.register(new SimpleExtensionHandler("stardist",
            "https://github.com/qupath/qupath-extension-stardist/releases",
            Compat.FULL));
        registry.register(new SimpleExtensionHandler("wsinfer",
            "https://github.com/qupath/qupath-extension-wsinfer/releases",
            Compat.PARTIAL,
            "Model weights must be pre-downloaded on the replay machine."));
        registry.register(new SimpleExtensionHandler("bioimageio",
            "https://github.com/qupath/qupath-extension-bioimageio/releases",
            Compat.PARTIAL,
            "Model weights must be pre-downloaded on the replay machine."));
        registry.register(new SimpleExtensionHandler("djl",
            "https://github.com/qupath/qupath-extension-djl/releases",
            Compat.PARTIAL,
            "Model files must be present at the same local path on the replay machine."));
        registry.register(new SimpleExtensionHandler("omero",
            "https://github.com/qupath/qupath-extension-omero/releases",
            Compat.PARTIAL,
            "Server URL and credentials are not captured.\nThe OMERO server must be accessible from the replay machine."));
        registry.register(new SimpleExtensionHandler("tensorflow",
            "https://github.com/qupath/qupath-extension-tensorflow/releases",
            Compat.PARTIAL,
            "Model path must point to a valid SavedModel directory on the replay machine."));
        registry.register(new SimpleExtensionHandler("training",
            "https://github.com/qupath/qupath-extension-training/releases",
            Compat.FULL));
        registry.register(new SimpleExtensionHandler("cellpose",
            "https://github.com/BIOP/qupath-extension-cellpose/releases",
            Compat.PARTIAL,
            "A running Cellpose server is required on the replay machine."));
        registry.register(new SimpleExtensionHandler("warpy",
            "https://github.com/BIOP/qupath-extension-warpy/releases",
            Compat.PARTIAL,
            "Registration transformation files must be present at the same path on the replay machine."));
        registry.register(new SimpleExtensionHandler("imagecombiner",
            "https://github.com/BIOP/qupath-extension-image-combine/releases",
            Compat.PARTIAL,
            "Warpy transformation files must be present at the same path on the replay machine."));
        registry.register(new SimpleExtensionHandler("biop",
            "https://github.com/BIOP/qupath-extension-biop/releases",
            Compat.FULL));
        registry.register(new SimpleExtensionHandler("sam",
            "https://github.com/ksugar/qupath-extension-sam/releases",
            Compat.NOT_SCRIPTABLE,
            "SAM operates interactively — clicks and brush strokes cannot be captured.\nManual re-annotation required on the replay machine."));
        registry.register(new SimpleExtensionHandler("imagej",
            "https://github.com/qupath/qupath-extension-ij/releases",
            Compat.PARTIAL,
            "Macro content is not captured — only the call is recorded.\nMacro files must exist at the same path on the replay machine."));
        registry.register(new SimpleExtensionHandler("svg",
            "https://github.com/qupath/qupath-extension-svg/releases",
            Compat.FULL));
    }
}
