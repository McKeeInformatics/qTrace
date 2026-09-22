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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File-level half of the auto-updater: writing a downloaded module JAR into the
 * extensions directory and removing the versions it supersedes. No JavaFX, no QuPath,
 * no logging dependency — so it runs (and is tested) on any OS, including the Windows
 * CI runner where a loaded JAR is locked and cannot be deleted.
 */
public final class JarInstaller {

    private JarInstaller() {}

    private static final Pattern JAR_VERSION =
        Pattern.compile("^qtrace-(?:core|compliance)-(\\d+(?:\\.\\d+)*)\\.jar$");

    /** What {@link #reap} did: the version kept, the files removed, the ones it could not remove. */
    public record ReapResult(String kept, List<Path> deleted, Map<Path, String> failed) {}

    /** Writes {@code data} as {@code qtrace-<module>-<version>.jar} in {@code dir} (atomic rename). */
    public static Path install(Path dir, String module, String version, byte[] data) throws Exception {
        Path target = dir.resolve("qtrace-" + module + "-" + version + ".jar");
        Path tmp = dir.resolve("qtrace-" + module + "-" + version + ".jar.part");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * Removes versioned JARs for {@code module} older than the highest present, plus the
     * legacy unversioned {@code qtrace-<module>.jar} whenever a versioned JAR exists
     * (best-effort: failures are reported, not thrown).
     */
    public static ReapResult reap(Path dir, String module) {
        List<Path> deleted = new ArrayList<>();
        Map<Path, String> failed = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return new ReapResult(null, deleted, failed);
        String prefix = "qtrace-" + module + "-";
        String bareLegacyName = "qtrace-" + module + ".jar";
        String best = null;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String v = versionOf(p, prefix);
                if (v != null && (best == null || compareSemver(v, best) > 0)) best = v;
            }
        } catch (Exception e) {
            failed.put(dir, e.toString());
            return new ReapResult(null, deleted, failed);
        }
        if (best == null) return new ReapResult(null, deleted, failed);
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String v = versionOf(p, prefix);
                boolean isBareLegacy = p.getFileName().toString().equals(bareLegacyName);
                if (isBareLegacy || (v != null && compareSemver(v, best) < 0)) {
                    try {
                        Files.deleteIfExists(p);
                        deleted.add(p);
                    } catch (Exception e) {
                        failed.put(p, e.toString());
                    }
                }
            }
        } catch (Exception e) {
            failed.put(dir, e.toString());
        }
        return new ReapResult(best, deleted, failed);
    }

    private static String versionOf(Path p, String prefix) {
        String name = p.getFileName().toString();
        if (!name.startsWith(prefix) || !name.endsWith(".jar")) return null;
        Matcher m = JAR_VERSION.matcher(name);
        return m.matches() ? m.group(1) : null;
    }

    /** Compares dotted numeric versions. >0 if a>b, 0 equal, <0 a<b. */
    public static int compareSemver(String a, String b) {
        if (a == null) a = "0";
        if (b == null) b = "0";
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parse(pa[i]) : 0;
            int vb = i < pb.length ? parse(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); }
        catch (Exception e) { return 0; }
    }
}
