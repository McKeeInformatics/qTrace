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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.ObjectClassifierFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaying with exactly the object classifier that was recorded.
 *
 * <p>A .qtrace records, per saved object classifier, the fingerprint of its file and the name of the copy
 * that travels beside it ({@link ObjectClassifierFiles}). Before a replay, {@link #prepare} checks each
 * copy against its fingerprint and hands the verified ones to the replay; {@link #restoreBlocks} writes the
 * script that puts them back in the project - or says plainly that this replay will not use the recorded
 * classifier. A file that is missing or differs is never replayed in its place. Pure: no JavaFX, no network.
 */
final class ObjectClassifierReplay {

    private ObjectClassifierReplay() {}

    /** Sub-folder of the Player's temp folder that holds the verified copies. */
    static final String DIR = "object_classifiers";

    /** How the pre-flight row is coloured. */
    enum Level { OK, WARN, BAD }

    /** What the pre-flight check knows about the object classifiers a record needs, by classifier name. */
    record Result(List<String> verified, Map<String, String> missing, Map<String, String> differs,
                  Map<String, String> notCaptured) {
        boolean none() { return verified.isEmpty() && problems() == 0; }

        Level level() {
            return !missing.isEmpty() || !differs.isEmpty() ? Level.BAD : !notCaptured.isEmpty() ? Level.WARN : Level.OK;
        }

        /** The pre-flight row's text: every problem with the classifiers' names, then how many are verified. */
        String summary() {
            List<String> parts = new ArrayList<>();
            if (!missing.isEmpty()) parts.add(missing.size() + " missing: " + String.join(", ", missing.keySet()));
            if (!differs.isEmpty())
                parts.add(differs.size() + " changed since it was recorded (sha256 differs): " + String.join(", ", differs.keySet()));
            if (!notCaptured.isEmpty())
                parts.add(notCaptured.size() + " recorded before qTrace kept their content, cannot be replayed exactly: "
                    + String.join(", ", notCaptured.keySet()));
            if (!verified.isEmpty()) parts.add(verified.size() + " verified by sha256");
            return String.join("; ", parts);
        }
        boolean allVerified() { return problems() == 0 && !verified.isEmpty(); }
        int problems() { return missing.size() + differs.size() + notCaptured.size(); }
    }

    /** The version of each name a replay of that scope uses: the last one saved up to the scope's last session. */
    static List<JsonObject> inScope(String qtraceJson, int sessionIndex) {
        JsonArray sessions = sessions(qtraceJson);
        int end = sessionIndex < 0 ? sessions.size() : Math.min(sessionIndex + 1, sessions.size());
        Map<String, JsonObject> byName = new LinkedHashMap<>();
        for (int i = 0; i < end; i++)
            for (JsonObject rec : records(sessions.get(i))) byName.put(name(rec), rec);
        return new ArrayList<>(byName.values());
    }

    /** Every distinct (name, fingerprint) saved anywhere in the record, in the order saved. */
    static List<JsonObject> versions(String qtraceJson) {
        Map<String, JsonObject> distinct = new LinkedHashMap<>();
        for (JsonElement s : sessions(qtraceJson))
            for (JsonObject rec : records(s))
                distinct.putIfAbsent(name(rec) + "@" + (rec.has("sha256") ? rec.get("sha256").getAsString() : ""), rec);
        return new ArrayList<>(distinct.values());
    }

    /**
     * Verifies every kept copy of a local record against its fingerprint and copies the good ones to
     * {@code tempDir/object_classifiers/}. The result reports each classifier by the status of its latest
     * version: an older version that is gone only matters if a replay of that session is asked for, and the
     * script warns then.
     */
    static Result prepare(String source, String qtraceJson, Path tempDir) {
        Map<String, JsonObject> latest = new LinkedHashMap<>();
        inScope(qtraceJson, -1).forEach(r -> latest.put(name(r), r));
        List<String> verified = new ArrayList<>();
        Map<String, String> missing = new LinkedHashMap<>(), differs = new LinkedHashMap<>(), notCaptured = new LinkedHashMap<>();
        if (latest.isEmpty()) return new Result(verified, missing, differs, notCaptured);

        boolean local = RecordSource.parse(source).local();
        Path beside = local ? ObjectClassifierFiles.companionDir(Path.of(source.strip())) : null;
        Map<String, ObjectClassifierFiles.Check> checkOfVersion = new LinkedHashMap<>();
        for (JsonObject v : versions(qtraceJson)) {
            ObjectClassifierFiles.Check check;
            Path file = beside == null || !v.has("classifier_file") ? null
                : beside.resolve(Path.of(v.get("classifier_file").getAsString()).getFileName());
            if (!local && v.has("sha256")) {
                check = new ObjectClassifierFiles.Check(ObjectClassifierFiles.Status.MISSING,
                    "the workspace does not serve object classifier files yet - replay from the local .qtrace and its .files folder");
            } else {
                check = ObjectClassifierFiles.verify(v, file);
            }
            checkOfVersion.put(key(v), check);
            if (check.status() == ObjectClassifierFiles.Status.OK) {
                try {
                    Path dir = tempDir.resolve(DIR);
                    Files.createDirectories(dir);
                    Files.copy(file, dir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    checkOfVersion.put(key(v), new ObjectClassifierFiles.Check(ObjectClassifierFiles.Status.MISSING,
                        "could not stage " + file.getFileName() + ": " + e.getMessage()));
                }
            }
        }
        for (JsonObject r : latest.values()) {
            ObjectClassifierFiles.Check c = checkOfVersion.get(key(r));
            switch (c.status()) {
                case OK -> verified.add(name(r));
                case MISSING -> missing.put(name(r), c.reason());
                case DIFFERS -> differs.put(name(r), c.reason());
                case NOT_CAPTURED -> notCaptured.put(name(r), c.reason());
            }
        }
        return new Result(verified, missing, differs, notCaptured);
    }

    /** The script lines that put each classifier a replay of that scope needs back into the open project. */
    static String restoreBlocks(String qtraceJson, int sessionIndex, Path classifierDir) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonObject rec : inScope(qtraceJson, sessionIndex)) {
            String name = name(rec);
            Path file = classifierDir == null || !rec.has("classifier_file") ? null
                : classifierDir.resolve(DIR).resolve(Path.of(rec.get("classifier_file").getAsString()).getFileName());
            String q = groovy(name);
            boolean plain = !name.contains("/") && !name.contains("\\") && !name.contains("..");
            if (file == null || !Files.isRegularFile(file) || !plain) {
                String why = !rec.has("sha256") ? "it was saved before qTrace kept the content of object classifiers"
                    : !plain ? "its name is not a plain file name"
                    : "its file is missing or does not match the recorded sha256";
                sb.append("println('[QTrace] WARNING: object classifier \"").append(q).append("\" cannot be restored (")
                  .append(groovy(why)).append(") - this replay will NOT use the recorded classifier');\n\n");
                continue;
            }
            try {
                String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
                // A closure called by name, never a bare block: Groovy would read a line starting with { after a call as that call's closure
                String fn = "_restoreObjectClassifier" + (++n);
                sb.append("// Restore object classifier: ").append(name).append(" (sha256 ")
                  .append(rec.get("sha256").getAsString(), 0, 12).append(")\n");
                sb.append("def ").append(fn).append(" = { ->\n");
                // 60000-char chunks stay under the JVM's 65535-char string literal limit
                int chunk = 60000;
                sb.append("  def _b64 = \"").append(b64, 0, Math.min(chunk, b64.length())).append("\"");
                for (int pos = chunk; pos < b64.length(); pos += chunk)
                    sb.append(" +\n      \"").append(b64, pos, Math.min(pos + chunk, b64.length())).append("\"");
                sb.append("\n");
                sb.append("  def _bytes = java.util.Base64.getDecoder().decode(_b64)\n");
                sb.append("  def _proj = getProject()\n");
                sb.append("  if (_proj == null) {\n");
                sb.append("    println('⚠ No project open - object classifier ").append(q).append(" not restored; open a QuPath project before running');\n");
                sb.append("  } else {\n");
                sb.append("    def _dir = new java.io.File(_proj.getPath().toFile().parentFile, 'classifiers/object_classifiers')\n");
                sb.append("    _dir.mkdirs()\n");
                sb.append("    def _f = new java.io.File(_dir, '").append(q).append(".json')\n");
                sb.append("    if (_f.exists() && java.util.Arrays.equals(_f.bytes, _bytes)) {\n");
                sb.append("      println('✓ Object classifier ").append(q).append(": the project already holds this exact file (identical)');\n");
                sb.append("    } else {\n");
                sb.append("      if (_f.exists()) {\n");
                sb.append("        def _old = new java.io.File(_dir, '").append(q).append(".json.replaced-' + System.currentTimeMillis())\n");
                sb.append("        _f.renameTo(_old)\n");
                sb.append("        println('⚠ Object classifier ").append(q).append(": the project had a different file, kept as ' + _old.name);\n");
                sb.append("      }\n");
                sb.append("      _f.bytes = _bytes\n");
                sb.append("      println('✓ Object classifier ").append(q).append(" restored to the project');\n");
                sb.append("    }\n");
                sb.append("  }\n");
                sb.append("}\n").append(fn).append("();\n\n");
            } catch (IOException e) {
                sb.append("println('[QTrace] WARNING: object classifier \"").append(q).append("\" cannot be read (")
                  .append(groovy(String.valueOf(e.getMessage()))).append(") - this replay will NOT use the recorded classifier');\n\n");
            }
        }
        return sb.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String key(JsonObject rec) { return name(rec) + "@" + (rec.has("sha256") ? rec.get("sha256").getAsString() : ""); }

    private static String name(JsonObject rec) { return rec.get("name").getAsString(); }

    private static String groovy(String s) { return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " "); }

    private static JsonArray sessions(String qtraceJson) {
        try {
            JsonElement el = JsonParser.parseString(qtraceJson);
            if (el.isJsonObject() && el.getAsJsonObject().has("sessions") && el.getAsJsonObject().get("sessions").isJsonArray())
                return el.getAsJsonObject().getAsJsonArray("sessions");
        } catch (RuntimeException ignored) {
            // not a record: nothing to restore
        }
        return new JsonArray();
    }

    private static List<JsonObject> records(JsonElement session) {
        List<JsonObject> out = new ArrayList<>();
        if (!session.isJsonObject() || !session.getAsJsonObject().has("object_classifiers")
                || !session.getAsJsonObject().get("object_classifiers").isJsonArray()) return out;
        for (JsonElement el : session.getAsJsonObject().getAsJsonArray("object_classifiers"))
            if (el.isJsonObject() && el.getAsJsonObject().has("name") && el.getAsJsonObject().get("name").isJsonPrimitive()
                    && !el.getAsJsonObject().get("name").getAsString().isBlank()) out.add(el.getAsJsonObject());
        return out;
    }
}
