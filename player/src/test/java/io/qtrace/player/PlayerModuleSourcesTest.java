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

import io.qtrace.CoreTestAccess;
import io.qtrace.PlayerSource;
import io.qtrace.PlayerSources;

import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** The player's Open entries list the sources of the modules the licence includes, and no other. */
class PlayerModuleSourcesTest {

    private static final PlayerSource TRAINING = new PlayerSource() {
        @Override public String label() { return "Training…"; }
        @Override public void choose(Window owner, Consumer<String> open) { open.accept("qtc_1"); }
    };

    @AfterEach
    void clear() {
        CoreTestAccess.clearPlayerSources();
        CoreTestAccess.setServedModules(null);
    }

    @Test
    void anEntitledModuleSourceIsOffered() {
        PlayerSources.register("training", TRAINING);
        CoreTestAccess.setServedModules(Set.of("compliance", "training"));
        assertThat(QTraceReplayPlayer.moduleSources()).containsExactly(TRAINING);
    }

    @Test
    void aModuleTheLicenceDoesNotIncludeIsNotOffered() {
        PlayerSources.register("training", TRAINING);
        CoreTestAccess.setServedModules(Set.of("compliance"));
        assertThat(QTraceReplayPlayer.moduleSources()).isEmpty();
    }
}
