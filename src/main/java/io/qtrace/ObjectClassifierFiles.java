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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.qtrace.chain.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * The record qTrace keeps of an object classifier, and the copy of its file that travels with a
 * .qtrace so the Player can replay with exactly that classifier.
 *
 * <p>The record ({@code sessions[].object_classifiers[]}) keeps the four fields it always had and adds
 * {@code sha256} (of the file QuPath wrote), {@code classifier_file} (the copy's name, which carries the
 * first 12 characters of the fingerprint, so two versions of one name never overwrite each other) and
 * {@code content} (see {@link ObjectClassifierContent}). The copy sits in
 * {@code <file>.qtrace.files/object_classifiers/}; a .qtrace written before this existed has neither,
 * and {@link #verify} says "not captured" rather than guessing.
 */
public final class ObjectClassifierFiles {

    private ObjectClassifierFiles() {}

    public enum Status {
        /** The file is there and is the one that was recorded. */
        OK,
        /** The file is not where the record says. */
        MISSING,
        /** A file is there but its fingerprint is not the recorded one: it is another classifier. */
        DIFFERS,
        /** The record predates the content capture: nothing to check against. */
        NOT_CAPTURED
    }

    public record Check(Status status, String reason) {}

    public static JsonObject record(String name, byte[] raw, Instant savedAt, String user, boolean nameValid) {
        String sha = Hashing.sha256Hex(raw);
        JsonObject r = new JsonObject();
        r.addProperty("name", name);
        r.addProperty("saved_at", savedAt.toString());
        r.addProperty("saved_by_user", user);
        r.addProperty("name_policy_valid", nameValid);
        r.addProperty("sha256", sha);
        r.addProperty("classifier_file", fileName(name, sha));
        r.add("content", ObjectClassifierContent.summarize(new String(raw, StandardCharsets.UTF_8)));
        return r;
    }

    /** {@code <name>@<first 12 of the fingerprint>.json}; a name is a file name, never a path out of the folder. */
    public static String fileName(String name, String sha256) {
        String safe = name.replaceAll("[/\\\\]", "_").replace("..", "_");
        return safe + "@" + sha256.substring(0, Math.min(12, sha256.length())) + ".json";
    }

    /** Where the copies are kept at save time (next to the pixel classifiers' copies). */
    public static Path copiesDir() {
        return QTraceConfig.get().outputClassifierDir().resolve("classifiers").resolve("object_classifiers");
    }

    public static Path companionDir(Path qtraceFile) {
        return qtraceFile.resolveSibling(qtraceFile.getFileName() + ".files").resolve("object_classifiers");
    }

    /** Writes the exact bytes QuPath wrote, at save time, so the export still has them if the project file changes later. */
    public static Path keepCopy(Path copiesDir, JsonObject record, byte[] raw) {
        Path copy = copiesDir.resolve(Path.of(record.get("classifier_file").getAsString()).getFileName());
        try {
            Files.createDirectories(copiesDir);
            Files.write(copy, raw);
        } catch (IOException e) {
            ActivityLog.add("  WARNING: could not keep a copy of object classifier " + record.get("name").getAsString()
                + " - " + e.getMessage());
        }
        return copy;
    }

    /** Copies each record's kept file beside the .qtrace; a record without a kept copy is skipped. Returns how many were copied. */
    public static int exportCompanions(JsonArray records, Path copiesDir, Path qtraceFile) {
        int copied = 0;
        for (JsonElement el : records) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("classifier_file")) continue;
            String file = Path.of(el.getAsJsonObject().get("classifier_file").getAsString()).getFileName().toString();
            Path from = copiesDir.resolve(file);
            if (!Files.isRegularFile(from)) continue;
            try {
                Path dir = companionDir(qtraceFile);
                Files.createDirectories(dir);
                Files.copy(from, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException e) {
                ActivityLog.add("  WARNING: could not copy object classifier file " + file + " - " + e.getMessage());
            }
        }
        return copied;
    }

    /** The Logbook's line for a saved classifier: its name, then the rule when the record has one. */
    public static String eventText(JsonObject record) {
        String rule = record.has("content") && record.get("content").isJsonObject()
            ? ObjectClassifierContent.headline(record.getAsJsonObject("content")) : "";
        return "Object classifier saved: " + record.get("name").getAsString() + (rule.isEmpty() ? "" : " (" + rule + ")");
    }

    public static Check verify(JsonObject record, Path file) {
        if (record == null || !record.has("sha256"))
            return new Check(Status.NOT_CAPTURED, "recorded before the classifier's content was captured");
        if (file == null || !Files.isRegularFile(file))
            return new Check(Status.MISSING, "file not found: " + (file == null ? "?" : file.getFileName()));
        String expected = record.get("sha256").getAsString();
        try {
            String actual = Hashing.sha256Hex(Files.readAllBytes(file));
            if (actual.equals(expected)) return new Check(Status.OK, "");
            return new Check(Status.DIFFERS, "sha256 differs: recorded " + expected.substring(0, Math.min(12, expected.length()))
                + ", found " + actual.substring(0, 12));
        } catch (IOException e) {
            return new Check(Status.MISSING, "cannot read " + file.getFileName() + ": " + e.getMessage());
        }
    }
}
