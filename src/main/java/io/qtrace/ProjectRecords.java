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
import com.google.gson.JsonParser;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The images of the open project, each with its .qtrace when it has one — what the Dashboard
 * lists, shared with the modules that draw the same records another way (e.g. the cohort map).
 */
public final class ProjectRecords {

    private ProjectRecords() {}

    /** One image: {@code qtrace} and {@code qtraceFile} are null while nothing was recorded for it. */
    public record Row(String imageName, JsonObject qtrace, File qtraceFile) {}

    public record Scan(List<Row> rows, int qtraceCount, String dirPath) {}

    /** Scans the export directory + current project for every .qtrace / image. Off-FX-thread only. */
    public static Scan scan(QuPathGUI qupath) {
        File qtDir = null;
        // Project Folder mode: <project>/qTrace/trace, or nothing without an open project
        // — never the configured fallback.
        try { qtDir = QTraceConfig.get().readExportDir().map(Path::toFile).orElse(null); } catch (Exception ignored) {}

        List<String> names = new ArrayList<>();
        try {
            var project = qupath.getProject();
            if (project != null)
                for (var entry : project.getImageList()) names.add(entry.getImageName());
        } catch (Exception ignored) {}
        return scan(names, qtDir);
    }

    /** Same, from the project's image names and the folder holding the .qtrace files (may be null). */
    static Scan scan(List<String> projectImageNames, File qtDir) {
        Map<String, JsonObject> qtraceMap = new LinkedHashMap<>();
        Map<String, File>       fileMap   = new LinkedHashMap<>();
        if (qtDir != null && qtDir.exists() && qtDir.isDirectory()) {
            File[] files = qtDir.listFiles((d, n) -> n.endsWith(".qtrace"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    try {
                        JsonObject root = JsonParser.parseString(
                            Files.readString(f.toPath())).getAsJsonObject();
                        // image name is always at root.image.name (format 2.0)
                        String fallback = f.getName().replace(".qtrace", "");
                        JsonObject img = root.has("image") && root.get("image").isJsonObject()
                            ? root.getAsJsonObject("image") : null;
                        String imgName = img != null && img.has("name") && !img.get("name").isJsonNull()
                            ? img.get("name").getAsString() : fallback;
                        qtraceMap.put(imgName, root);
                        fileMap.put(imgName, f);
                    } catch (Exception ignored) {}
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();
        for (String name : projectImageNames) {
            added.add(name);
            rows.add(new Row(name, findMatching(name, qtraceMap), findMatching(name, fileMap)));
        }
        for (var e : qtraceMap.entrySet()) {
            boolean already = added.stream().anyMatch(n -> namesMatch(n, e.getKey()));
            if (!already) rows.add(new Row(e.getKey(), e.getValue(), fileMap.get(e.getKey())));
        }
        return new Scan(rows, qtraceMap.size(), qtDir != null ? qtDir.getAbsolutePath() : null);
    }

    private static <T> T findMatching(String name, Map<String, T> map) {
        for (var e : map.entrySet())
            if (namesMatch(name, e.getKey())) return e.getValue();
        return null;
    }

    /** A project image and a .qtrace's image are the same when one name contains the other. */
    public static boolean namesMatch(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /** The project entry of an image, or null (no project, image gone). */
    public static ProjectImageEntry<?> entryOf(QuPathGUI qupath, String imageName) {
        try {
            var project = qupath.getProject();
            if (project == null || imageName == null) return null;
            for (var entry : project.getImageList())
                if (namesMatch(entry.getImageName(), imageName)) return entry;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Integrity of a row's latest stamp against the work saved for {@code entry} (null = unknown,
     * then only the stamp itself is checked) — the full {@link StampIntegrity#check}. Hashes the
     * .qpdata: off-FX-thread only.
     */
    public static StampIntegrity.State integrity(Row row, ProjectImageEntry<?> entry) {
        if (row.qtrace() == null) return StampIntegrity.State.NO_STAMP;
        String sha = null;
        try {
            if (entry != null && entry.getEntryPath() != null) {
                Path qpdata = entry.getEntryPath().resolve("data.qpdata");
                if (Files.exists(qpdata)) sha = io.qtrace.chain.Hashing.sha256Hex(qpdata);
            }
        } catch (Exception ignored) {}
        Path exportDir = row.qtraceFile() != null ? row.qtraceFile().toPath().getParent() : null;
        return StampIntegrity.check(row.qtrace(), sha, StampIntegrity.findCertPayload(row.qtrace(), exportDir),
            exportDir, QTraceConfig.get().outputTrainingDir());
    }
}
