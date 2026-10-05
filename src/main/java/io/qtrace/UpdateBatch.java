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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every qTrace update found by one startup check, shown as one message instead of one per
 * extension: what it lists and what "skip" remembers. No JavaFX, no network: unit-tested;
 * {@link QTraceUpdater} collects the items and shows the message.
 */
final class UpdateBatch {

    /** One extension to update. {@code currentVer} "0" (or blank): not installed yet. */
    record Item(String module, String currentVer, String remoteVer, String sha256, QTraceUpdater.Downloader downloader) {}

    private static final List<String> FIRST = List.of("core", "compliance");

    private final List<Item> items;

    private UpdateBatch(List<Item> items) {
        this.items = items;
    }

    /** One item per extension — its highest offered version — Core and Compliance first, then by name. */
    static UpdateBatch of(List<Item> offered) {
        Map<String, Item> byModule = new LinkedHashMap<>();
        for (Item it : offered) {
            Item known = byModule.get(it.module());
            if (known == null || JarInstaller.compareSemver(it.remoteVer(), known.remoteVer()) > 0) byModule.put(it.module(), it);
        }
        List<Item> sorted = new ArrayList<>(byModule.values());
        sorted.sort((a, b) -> {
            int ra = rank(a.module()), rb = rank(b.module());
            return ra != rb ? Integer.compare(ra, rb) : a.module().compareTo(b.module());
        });
        return new UpdateBatch(List.copyOf(sorted));
    }

    private static int rank(String module) {
        int i = FIRST.indexOf(module);
        return i < 0 ? FIRST.size() : i;
    }

    List<Item> items() {
        return items;
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /** "Core  1.2.4 → 1.2.5", or "Training  0.1.0 (new)" for an extension not installed yet. */
    List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Item it : items) {
            boolean fresh = it.currentVer() == null || it.currentVer().isBlank() || "0".equals(it.currentVer());
            out.add(moduleLabel(it.module()) + "  " + (fresh ? it.remoteVer() + " (new)" : it.currentVer() + " → " + it.remoteVer()));
        }
        return out;
    }

    /** What the post-install message names: "Core v1.2.5". */
    static String label(Item it) {
        return moduleLabel(it.module()) + " v" + it.remoteVer();
    }

    static String moduleLabel(String module) {
        if ("core".equals(module)) return "Core";
        if ("compliance".equals(module)) return "Compliance";
        return module.isEmpty() ? module : Character.toUpperCase(module.charAt(0)) + module.substring(1);
    }

    /** Exactly this set of versions, whatever the order they were found in. */
    String signature() {
        return String.join(",", items.stream().map(it -> it.module() + "@" + it.remoteVer()).sorted().toList());
    }

    /**
     * True when the user already chose to skip exactly these versions. Also honours what qTrace
     * stored before the single message — the bare version of the one extension it had asked about.
     */
    boolean wasSkipped(String dismissed) {
        if (dismissed == null || dismissed.isBlank() || items.isEmpty()) return false;
        return dismissed.equals(signature()) || (items.size() == 1 && dismissed.equals(items.get(0).remoteVer()));
    }
}
