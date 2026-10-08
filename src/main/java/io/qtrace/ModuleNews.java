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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which version of each module this workstation was last shown — so that an update can be
 * presented once (what the new version brings), and never again. Kept in
 * {@code ~/.qTrace/modules-seen.json}. A module seen for the first time is not news: it was just
 * installed, and its presentation is Getting started's. The rule is pure and unit-tested; who
 * plays the slides is the Welcome module.
 */
public final class ModuleNews {

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".qTrace", "modules-seen.json");

    private ModuleNews() {}

    // ── Rule (pure) ──────────────────────────────────────────────────────────────

    /** The installed modules whose version is newer than the one last shown, with that version. */
    static Map<String, String> updated(Map<String, String> installed, Map<String, String> seen) {
        Map<String, String> out = new TreeMap<>();
        installed.forEach((module, version) -> {
            String shown = seen.get(module);
            if (shown != null && JarInstaller.compareSemver(version, shown) > 0) out.put(module, version);
        });
        return out;
    }

    /** What to remember once the installed versions were shown: them, over what was known. */
    static Map<String, String> afterShowing(Map<String, String> installed, Map<String, String> seen) {
        Map<String, String> out = new TreeMap<>(seen);
        out.putAll(installed);
        return out;
    }

    // ── On disk ──────────────────────────────────────────────────────────────────

    /** null when nothing was remembered yet (or the file cannot be read). */
    static Map<String, String> read(Path file) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("seen");
            Map<String, String> out = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static void write(Path file, Map<String, String> seen) {
        try {
            JsonObject versions = new JsonObject();
            new TreeMap<>(seen).forEach(versions::addProperty);
            JsonObject o = new JsonObject();
            o.add("seen", versions);
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString());
        } catch (Exception ignored) {}
    }

    // ── For the module that plays the slides ─────────────────────────────────────

    /** The modules installed on this workstation, with their version (extensions/qtrace/). */
    public static Map<String, String> installed() {
        return ModuleUpdates.localVersions(QTraceUpdater.extensionsDir(QTraceUpdater.class));
    }

    /**
     * The modules updated since their version was last shown, with their new version. The very
     * first time, everything installed is taken as known: nothing to show.
     */
    public static Map<String, String> updatedSinceLastShown() {
        Map<String, String> seen = read(FILE);
        if (seen == null) {
            write(FILE, installed());
            return Map.of();
        }
        return updated(installed(), seen);
    }

    /** The versions installed now have been shown (or needed no showing): remembered. */
    public static void markShown() {
        Map<String, String> seen = read(FILE);
        write(FILE, afterShowing(installed(), seen == null ? Map.of() : seen));
    }
}
