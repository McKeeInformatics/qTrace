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

import io.qtrace.player.PlayerRecents.Entry;
import io.qtrace.player.PlayerRecents.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The last artefacts the player opened: what is kept, in which order, and where. */
class PlayerRecentsTest {

    private static final long NOW = 1_800_000_000_000L;

    private static Entry entry(String source, long at) {
        return new Entry(source, Kind.FILE, "t", at);
    }

    @Test
    void theLastOpenedComesFirst() {
        List<Entry> list = PlayerRecents.remember(List.of(), entry("/a.qtrace", 1));
        list = PlayerRecents.remember(list, entry("/b.qtrace", 2));
        assertThat(list).extracting(Entry::source).containsExactly("/b.qtrace", "/a.qtrace");
    }

    @Test
    void reopeningMovesTheEntryUpInsteadOfAddingIt() {
        List<Entry> list = PlayerRecents.remember(List.of(), entry("/a.qtrace", 1));
        list = PlayerRecents.remember(list, entry("/b.qtrace", 2));
        list = PlayerRecents.remember(list, new Entry("/a.qtrace", Kind.FILE, "renamed", 3));
        assertThat(list).extracting(Entry::source).containsExactly("/a.qtrace", "/b.qtrace");
        assertThat(list.get(0).title()).isEqualTo("renamed");
        assertThat(list.get(0).openedAt()).isEqualTo(3);
    }

    @Test
    void keepsTheFiveLast() {
        List<Entry> list = List.of();
        for (int i = 0; i < 8; i++) list = PlayerRecents.remember(list, entry("/" + i + ".qtrace", i));
        assertThat(list).hasSize(PlayerRecents.MAX).hasSize(5);
        assertThat(list).extracting(Entry::source)
            .containsExactly("/7.qtrace", "/6.qtrace", "/5.qtrace", "/4.qtrace", "/3.qtrace");
    }

    @Test
    void theSameRecordGivenTwoWaysIsOneEntry() {
        assertThat(PlayerRecents.keyOf("qtc_abc"))
            .isEqualTo(PlayerRecents.keyOf("https://www.qtrace.ca/verify/qtc_abc"));
        assertThat(PlayerRecents.keyOf("/data/x/../slide.qtrace"))
            .isEqualTo(PlayerRecents.keyOf("/data/slide.qtrace"));
    }

    @Test
    void twoPacketsOfAWorkflowAreTwoEntries() {
        assertThat(PlayerRecents.keyOf("qtw_1#p1")).isNotEqualTo(PlayerRecents.keyOf("qtw_1#p2"));
    }

    @Test
    void twoShareLinksAreTwoEntries() {
        assertThat(PlayerRecents.keyOf("https://www.qtrace.ca/share/g/tok1"))
            .isNotEqualTo(PlayerRecents.keyOf("https://www.qtrace.ca/share/g/tok2"));
    }

    @Test
    void kindFollowsTheSourceAndTheContent() {
        assertThat(PlayerRecents.kindOf("/a.qtrace", false)).isEqualTo(Kind.FILE);
        assertThat(PlayerRecents.kindOf("qtc_abc", false)).isEqualTo(Kind.ONLINE);
        assertThat(PlayerRecents.kindOf("https://www.qtrace.ca/share/g/tok", false)).isEqualTo(Kind.SHARED);
        assertThat(PlayerRecents.kindOf("/w.qtflow", true)).isEqualTo(Kind.WORKFLOW);
        assertThat(PlayerRecents.kindOf("qtw_1#p1", true)).isEqualTo(Kind.WORKFLOW);
    }

    @Test
    void titleIsTheWorkflowsNameThenTheImagesThenTheSource() {
        assertThat(PlayerRecents.titleOf("/w.qtflow", "{\"kind\":\"workflow\",\"title\":\"Tumor segmentation\"}"))
            .isEqualTo("Tumor segmentation");
        assertThat(PlayerRecents.titleOf("qtc_abc", "{\"image\":{\"name\":\"Slide_042.svs\"}}"))
            .isEqualTo("Slide_042.svs");
        assertThat(PlayerRecents.titleOf("/data/slide.qtrace", "{}")).isEqualTo("slide.qtrace");
        assertThat(PlayerRecents.titleOf("qtc_abc", "not json")).isEqualTo("qtc_abc");
    }

    @Test
    void survivesARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("player-recents.json");
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("https://www.qtrace.ca/share/g/tok", Kind.SHARED, "Slide_9", 5));
        list.add(new Entry("/a.qtrace", Kind.FILE, "a", 4));
        PlayerRecents.write(file, list);
        assertThat(PlayerRecents.read(file)).isEqualTo(list);
    }

    @Test
    void theFileIsPrivateBecauseAShareLinkIsAKey(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("player-recents.json");
        PlayerRecents.write(file, List.of(entry("/a.qtrace", 1)));
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix"))
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void aMissingOrBrokenFileIsAnEmptyList(@TempDir Path dir) throws Exception {
        assertThat(PlayerRecents.read(dir.resolve("none.json"))).isEmpty();
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ not a list");
        assertThat(PlayerRecents.read(broken)).isEmpty();
        Path partial = dir.resolve("partial.json");
        Files.writeString(partial, "[{\"source\":\"/a.qtrace\",\"kind\":\"FILE\",\"title\":\"a\",\"openedAt\":1},"
            + "{\"kind\":\"FILE\"}, {\"source\":\"/b.qtrace\",\"kind\":\"SOMETHING_NEW\",\"openedAt\":2}]");
        assertThat(PlayerRecents.read(partial)).extracting(Entry::source).containsExactly("/a.qtrace", "/b.qtrace");
    }

    @Test
    void aLocalFileThatMovedIsUnavailable(@TempDir Path dir) throws Exception {
        Path here = Files.writeString(dir.resolve("a.qtrace"), "{}");
        assertThat(PlayerRecents.available(entry(here.toString(), 1))).isTrue();
        assertThat(PlayerRecents.available(entry(dir.resolve("gone.qtrace").toString(), 1))).isFalse();
        assertThat(PlayerRecents.available(new Entry("qtc_abc", Kind.ONLINE, "x", 1))).isTrue();
    }

    @Test
    void agoReadsLikeAPerson() {
        long min = 60_000, hour = 60 * min, day = 24 * hour;
        assertThat(PlayerRecents.ago(NOW - 10_000, NOW)).isEqualTo("just now");
        assertThat(PlayerRecents.ago(NOW - 5 * min, NOW)).isEqualTo("5 min ago");
        assertThat(PlayerRecents.ago(NOW - 2 * hour, NOW)).isEqualTo("2 h ago");
        assertThat(PlayerRecents.ago(NOW - 30 * hour, NOW)).isEqualTo("yesterday");
        assertThat(PlayerRecents.ago(NOW - 5 * day, NOW)).matches("[A-Z][a-z]{2} \\d{1,2}");
    }
}
