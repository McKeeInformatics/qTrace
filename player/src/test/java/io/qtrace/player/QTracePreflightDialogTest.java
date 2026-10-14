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

package io.qtrace.player;

import io.qtrace.player.ext.Compat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code check(...)} takes a QuPathGUI but every introspection call around it is already
 * defensively try/catch-wrapped (see getCurrentProjectName / getLoadedExtensionNames) —
 * passing null exercises exactly the "nothing loaded yet" path a Player would see before
 * QuPath finishes initializing, without needing to mock the GUI framework.
 */
class QTracePreflightDialogTest {

    @Test
    void check_noRequiredExtensions_isFullyCompatible() {
        var info = new QTraceReplayEngine.PreflightInfo("img.tiff", "proj", "0.5.1", Map.of(), Map.of());
        var result = QTracePreflightDialog.check(null, info);

        assertThat(result.overall()).isEqualTo(Compat.FULL);
        assertThat(result.extensions()).isEmpty();
    }

    @Test
    void check_unknownMissingExtension_downgradesToPartial() {
        var info = new QTraceReplayEngine.PreflightInfo(
            "img.tiff", "proj", "0.5.1", Map.of("totally-unknown-extension", "1.0"), Map.of());
        var result = QTracePreflightDialog.check(null, info);

        assertThat(result.overall()).isEqualTo(Compat.PARTIAL);
        assertThat(result.extensions()).hasSize(1);
        assertThat(result.extensions().get(0).present()).isFalse();
    }
}
