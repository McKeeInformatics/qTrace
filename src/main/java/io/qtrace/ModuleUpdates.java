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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Decision half of Core's updater when running under the qTrace loader
 * (docs/architecture/loader.md § 7): offers read from the loader's descriptors
 * (release qtrace-bootstrap.json + per-license /api/modules), compared with the modules
 * already in extensions/qtrace/. No JavaFX, no QuPath: unit-tested.
 */
public final class ModuleUpdates {

    private ModuleUpdates() {}

    /** One downloadable module version from a descriptor. */
    public record Offer(String module, String version, String name, String url, String sha256, boolean licensed) {}

    /** Entries of a descriptor ({"files": [{name, module, version, url, sha256}]}); incomplete ones skipped. */
    public static List<Offer> parse(String json, boolean licensed) {
        List<Offer> out = new ArrayList<>();
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (!o.has("files")) return out;
        for (JsonElement el : o.getAsJsonArray("files")) {
            JsonObject f = el.getAsJsonObject();
            String module = str(f, "module"), version = str(f, "version"), name = str(f, "name");
            String url = str(f, "url"), sha = str(f, "sha256");
            if (module == null || version == null || name == null || url == null || sha == null) continue;
            out.add(new Offer(module, version, name, url, sha, licensed));
        }
        return out;
    }

    /** Highest Qtrace-Version per Qtrace-Module among the .qtjar files of {@code dir} (manifest only). */
    public static Map<String, String> localVersions(Path dir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".qtjar")) continue;
                try (JarFile jf = new JarFile(p.toFile(), false)) {
                    var mf = jf.getManifest();
                    if (mf == null) continue;
                    String m = mf.getMainAttributes().getValue("Qtrace-Module");
                    String v = mf.getMainAttributes().getValue("Qtrace-Version");
                    if (m == null || v == null) continue;
                    String cur = out.get(m);
                    if (cur == null || JarInstaller.compareSemver(v, cur) > 0) out.put(m, v);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Offers strictly newer than the version of the same module already on disk (or absent). */
    public static List<Offer> pending(List<Offer> remote, Map<String, String> local) {
        return remote.stream().filter(o -> {
            String have = local.get(o.module());
            return have == null || JarInstaller.compareSemver(o.version(), have) > 0;
        }).toList();
    }

    /**
     * Folder holding the module file a class was loaded from — a .jar (direct install) or a
     * .qtjar (extensions/qtrace/ under the loader) — or the classes directory in dev; null
     * otherwise.
     */
    public static Path folderOf(Path codeSource) {
        if (codeSource == null) return null;
        if (Files.isDirectory(codeSource)) return codeSource;
        String n = codeSource.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(codeSource) && (n.endsWith(".jar") || n.endsWith(".qtjar")))
            return codeSource.getParent();
        return null;
    }

    /** True when Core was loaded from a .qtjar, i.e. through the qTrace loader. */
    public static boolean isLoaderMode(Path codeSource) {
        return codeSource != null
            && codeSource.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".qtjar");
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
