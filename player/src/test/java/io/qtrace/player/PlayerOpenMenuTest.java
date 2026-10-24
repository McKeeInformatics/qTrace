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

import io.qtrace.player.PlayerOpenMenu.Item;
import io.qtrace.player.PlayerOpenMenu.State;
import io.qtrace.player.PlayerOpenMenu.Type;
import io.qtrace.player.PlayerRecents.Entry;
import io.qtrace.player.PlayerRecents.Kind;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The entries of 📂 Open, the same in the mini-player and in the Player window. */
class PlayerOpenMenuTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final File TRACE = new File("/data/Slide_042.svs.qtrace");

    private static State state(String image, File trace, List<Entry> recents, boolean idEntry) {
        return new State(image, trace, List.of("Training…"), recents, e -> !e.source().contains("gone"), idEntry, NOW);
    }

    private static List<Type> types(List<Item> items) {
        return items.stream().map(Item::type).toList();
    }

    @Test
    void theOpenImagesTraceComesFirst() {
        List<Item> items = PlayerOpenMenu.items(state("Slide_042.svs", TRACE, List.of(), true));
        Item first = items.get(0);
        assertThat(first.type()).isEqualTo(Type.OPEN_IMAGE);
        assertThat(first.label()).isEqualTo("Trace of « Slide_042.svs »");
        assertThat(first.enabled()).isTrue();
        assertThat(first.source()).isEqualTo(TRACE.getAbsolutePath());
    }

    @Test
    void noImageOpenGreysTheEntryOutAndSaysWhy() {
        Item first = PlayerOpenMenu.items(state(null, null, List.of(), true)).get(0);
        assertThat(first.type()).isEqualTo(Type.OPEN_IMAGE);
        assertThat(first.enabled()).isFalse();
        assertThat(first.label()).isEqualTo("Trace of the open image");
        assertThat(first.detail()).isEqualTo("No image open");
    }

    @Test
    void anImageWithoutTraceGreysTheEntryOutAndSaysWhy() {
        Item first = PlayerOpenMenu.items(state("Slide_042.svs", null, List.of(), true)).get(0);
        assertThat(first.enabled()).isFalse();
        assertThat(first.label()).isEqualTo("Trace of « Slide_042.svs »");
        assertThat(first.detail()).isEqualTo("No trace yet for this image");
    }

    @Test
    void withoutRecentsThereIsNoRecentSection() {
        assertThat(types(PlayerOpenMenu.items(state(null, null, List.of(), true)))).containsExactly(
            Type.OPEN_IMAGE, Type.SEPARATOR, Type.LOCAL_FILE, Type.ID_OR_LINK, Type.SOURCE);
    }

    @Test
    void thePlayerWindowHasItsOwnFieldSoNoIdEntry() {
        assertThat(types(PlayerOpenMenu.items(state(null, null, List.of(), false)))).containsExactly(
            Type.OPEN_IMAGE, Type.SEPARATOR, Type.LOCAL_FILE, Type.SOURCE);
    }

    @Test
    void recentsComeLastWithTheirKindAndAgeThenClear() {
        List<Entry> recents = List.of(
            new Entry("qtw_1#p1", Kind.WORKFLOW, "Tumor segmentation", NOW - 2 * 3_600_000L),
            new Entry("/data/gone.qtrace", Kind.FILE, "Slide_009.svs", NOW - 60_000L));
        List<Item> items = PlayerOpenMenu.items(state(null, null, recents, true));
        assertThat(types(items)).containsExactly(
            Type.OPEN_IMAGE, Type.SEPARATOR, Type.LOCAL_FILE, Type.ID_OR_LINK, Type.SOURCE,
            Type.SEPARATOR, Type.HEADER, Type.RECENT, Type.RECENT, Type.CLEAR_RECENT);

        Item workflow = items.get(7);
        assertThat(workflow.label()).isEqualTo("Tumor segmentation");
        assertThat(workflow.detail()).isEqualTo("workflow · 2 h ago");
        assertThat(workflow.enabled()).isTrue();
        assertThat(workflow.source()).isEqualTo("qtw_1#p1");

        Item gone = items.get(8);
        assertThat(gone.enabled()).isFalse();
        assertThat(gone.detail()).isEqualTo("file not found");
        assertThat(gone.tip()).isEqualTo("/data/gone.qtrace");
    }

    @Test
    void theModuleSourcesKeepTheirOrderAndIndex() {
        State s = new State(null, null, List.of("Training…", "Library…"), List.of(), e -> true, true, NOW);
        List<Item> sources = PlayerOpenMenu.items(s).stream().filter(i -> i.type() == Type.SOURCE).toList();
        assertThat(sources).extracting(Item::label).containsExactly("Training…", "Library…");
        assertThat(sources).extracting(Item::index).containsExactly(0, 1);
    }
}
