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

import com.google.gson.JsonObject;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * What the workstation is made of, attached to a bug report so a problem can be told from
 * its environment: OS, processor, memory, graphics, Java, disk space — as short readable
 * strings, keyed for display.
 *
 * Deliberately never the machine name, the user's login, a network address or a file path:
 * the report ends up in a GitHub issue. File lists are names only.
 *
 * {@link #collect} may run a system command (processor / graphics card model) — call it off
 * the JavaFX thread. No JavaFX here: the screens are added by the dialog.
 */
final class SystemInfo {

    private static final long COMMAND_TIMEOUT_MS = 3_000;

    private SystemInfo() {}

    /** @param projectDir the open project's folder, only to read the free space of its disk; may be null */
    static JsonObject collect(Path projectDir) {
        JsonObject s = new JsonObject();
        put(s, "os", prop("os.name") + " " + prop("os.version") + " " + prop("os.arch"));
        put(s, "cpu", cpuModel());
        put(s, "cpuCores", String.valueOf(Runtime.getRuntime().availableProcessors()));
        put(s, "ram", ram());
        Runtime rt = Runtime.getRuntime();
        put(s, "jvmMemory", gb(rt.totalMemory() - rt.freeMemory()) + " used of " + gb(rt.maxMemory()) + " max");
        put(s, "gpu", gpu());
        put(s, "java", prop("java.version") + " (" + prop("java.vendor") + ")");
        put(s, "javafx", System.getProperty("javafx.runtime.version"));
        put(s, "locale", Locale.getDefault().toLanguageTag());
        put(s, "timezone", TimeZone.getDefault().getID());
        if (projectDir != null) put(s, "projectDisk", disk(projectDir));
        return s;
    }

    /** "8.2 GB free of 31.2 GB", or null when the JVM does not expose it. */
    private static String ram() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
                return gb(os.getFreeMemorySize()) + " free of " + gb(os.getTotalMemorySize());
        } catch (Throwable ignored) {}
        return null;
    }

    private static String disk(Path dir) {
        try {
            FileStore store = Files.getFileStore(dir);
            return gb(store.getUsableSpace()) + " free of " + gb(store.getTotalSpace()) + " (" + store.type() + ")";
        } catch (Exception e) {
            return null;
        }
    }

    static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ── Processor / graphics card model: not in the JVM, asked to the OS ──────

    private static String cpuModel() {
        String os = prop("os.name").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("linux"))
                return cpuModelFromCpuinfo(Files.readString(Path.of("/proc/cpuinfo")));
            if (os.contains("mac")) return run("sysctl", "-n", "machdep.cpu.brand_string");
            if (os.contains("win")) return System.getenv("PROCESSOR_IDENTIFIER");
        } catch (Exception ignored) {}
        return null;
    }

    static String cpuModelFromCpuinfo(String cpuinfo) {
        for (String line : cpuinfo.split("\n")) {
            if (!line.startsWith("model name")) continue;
            int colon = line.indexOf(':');
            if (colon >= 0) return line.substring(colon + 1).strip();
        }
        return null;
    }

    /** Graphics card(s): NVIDIA's own tool when there (name + memory), else what the OS lists. */
    private static String gpu() {
        String nvidia = run("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader");
        if (nvidia != null) return nvidia;
        String os = prop("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            String lspci = run("sh", "-c", "lspci | grep -iE 'vga|3d|display' | cut -d: -f3-");
            if (lspci != null) return lspci;
        }
        if (os.contains("mac"))
            return run("sh", "-c", "system_profiler SPDisplaysDataType | grep 'Chipset Model' | cut -d: -f2-");
        if (os.contains("win"))
            return run("powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_VideoController).Name -join '; '");
        return null;
    }

    /** Output of a short system command on one line, or null (missing, failed, too slow, empty). */
    private static String run(String... command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS) || p.exitValue() != 0) return null;
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : out.split("\\R")) if (!line.isBlank()) lines.add(line.strip());
            return lines.isEmpty() ? null : String.join("; ", lines);
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    // ── Installed files ───────────────────────────────────────────────────────

    /** Names (never paths) of the files of {@code dir} ending in {@code suffix}, sorted. */
    static List<String> fileNames(Path dir, String suffix) {
        List<String> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                 .map(f -> f.getFileName().toString())
                 .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(suffix))
                 .forEach(out::add);
        } catch (Exception ignored) {}
        Collections.sort(out);
        return out;
    }

    /** The folder this qTrace module was loaded from ({@code extensions/qtrace/}), or null if unknown. */
    static Path moduleDir() {
        try {
            Path jar = Path.of(SystemInfo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(jar) ? jar.getParent() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void put(JsonObject o, String key, String value) {
        if (value != null && !value.isBlank()) o.addProperty(key, value.strip());
    }

    private static String prop(String key) {
        return System.getProperty(key, "");
    }
}
