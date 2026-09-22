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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * Starts a detached helper that waits for process {@code pid} (the running QuPath)
     * to exit, then deletes {@code files}, retrying for ~20 s (an antivirus may still hold
     * a handle for a moment). For superseded JARs Windows refuses to delete while QuPath
     * has them open: without this, the old JAR is still there at the next start, sorts
     * first, gets loaded again and locked again — the update never takes effect.
     * Returns the helper process (callers normally ignore it).
     */
    public static Process scheduleDeleteAfterExit(long pid, Collection<Path> files) throws Exception {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            StringBuilder list = new StringBuilder();
            for (Path f : files) {
                if (list.length() > 0) list.append(',');
                list.append('\'').append(f.toAbsolutePath().toString().replace("'", "''")).append('\'');
            }
            String script = "$ErrorActionPreference='SilentlyContinue'\n"
                + "Wait-Process -Id " + pid + "\n"
                + "foreach ($f in @(" + list + ")) {\n"
                + "  for ($i = 0; $i -lt 40; $i++) {\n"
                + "    if (-not (Test-Path -LiteralPath $f)) { break }\n"
                + "    Remove-Item -LiteralPath $f -Force\n"
                + "    if (-not (Test-Path -LiteralPath $f)) { break }\n"
                + "    Start-Sleep -Milliseconds 500\n"
                + "  }\n"
                + "}\n";
            // -EncodedCommand (UTF-16LE base64): no quoting pitfalls with user paths.
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            cmd.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded));
        } else {
            // $0 = pid, "$@" = files. Ignore SIGHUP so closing QuPath's terminal doesn't kill us.
            cmd.addAll(List.of("sh", "-c",
                "trap '' HUP; while kill -0 \"$0\" 2>/dev/null; do sleep 0.5; done; "
                + "for f in \"$@\"; do i=0; while [ -e \"$f\" ] && [ $i -lt 40 ]; do "
                + "rm -f -- \"$f\"; [ -e \"$f\" ] && sleep 0.5; i=$((i+1)); done; done",
                Long.toString(pid)));
            for (Path f : files) cmd.add(f.toAbsolutePath().toString());
        }
        return new ProcessBuilder(cmd)
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .startsWith("windows") ? "NUL" : "/dev/null");
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
