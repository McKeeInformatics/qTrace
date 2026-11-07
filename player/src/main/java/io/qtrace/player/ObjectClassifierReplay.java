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
import java.time.Instant;
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
 * copy against its fingerprint and hands the verified ones to the replay. Each saved version becomes a step of the
 * replay ({@link #withCreations}), at its time among the instructions, whose script ({@link #creationFragment}) puts
 * it in the project only when the project does not already hold that exact file; the preamble carries the verified
 * copies as data ({@link #copiesBlock}). A file that is missing or differs is never replayed in its place: the step
 * fails and says why. Pure: no JavaFX, no network.
 */
final class ObjectClassifierReplay {

    private ObjectClassifierReplay() {}

    /** Sub-folder of the Player's temp folder that holds the verified copies. */
    static final String DIR = "object_classifiers";

    /** How the pre-flight row is coloured. */
    enum Level { OK, WARN, BAD }

    /** What the pre-flight check knows about the object classifiers a record needs, by classifier name. */
    record Result(List<String> verified, Map<String, String> missing, Map<String, String> differs,
                  Map<String, String> notCaptured, Map<String, String> embedded) {
        Result(List<String> verified, Map<String, String> missing, Map<String, String> differs, Map<String, String> notCaptured) {
            this(verified, missing, differs, notCaptured, Map.of());
        }
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
            if (!embedded.isEmpty()) {
                List<String> notes = new ArrayList<>();
                embedded.forEach((name, carrier) -> notes.add(name + " (embedded in " + carrier + ")"));
                parts.add("info, no file needed: " + String.join(", ", notes));
            }
            return String.join("; ", parts);
        }
        boolean allVerified() { return problems() == 0 && !verified.isEmpty(); }
        int problems() { return missing.size() + differs.size() + notCaptured.size(); }
    }

    /** How the files of a record opened by its ID are fetched: into the given folder (see {@link QTraceReplayEngine#downloadObjectClassifiers}). */
    @FunctionalInterface
    interface Downloader {
        QTraceReplayEngine.DownloadResult download(String certId, Path dir) throws Exception;
    }

    /**
     * The check of a record's object classifier files, wherever they are: beside a local record, or - for a
     * record opened by its ID ({@code certId}) - downloaded first into the Player's temp folder. A download that
     * cannot be done is said so in the answer, for each classifier, never an empty answer that reads as "none needed".
     */
    static Result resolve(String source, String qtraceJson, Path tempDir, String certId, Downloader downloader) {
        if (versions(qtraceJson).isEmpty() || RecordSource.parse(source).local()) return prepare(source, qtraceJson, tempDir);
        if (certId == null) return prepare(source, qtraceJson, tempDir, null, "the record has no ID to download its files from");
        Path downloaded = tempDir.resolve("downloaded_object_classifiers");
        try {
            downloader.download(certId, downloaded);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return prepare(source, qtraceJson, tempDir, null, "download interrupted");
        } catch (Exception e) {
            return prepare(source, qtraceJson, tempDir, null, "download failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
        return prepare(source, qtraceJson, tempDir, downloaded, null);
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
        return prepare(source, qtraceJson, tempDir, null, null, true);
    }

    /**
     * The same, for the files in {@code beside}: the folder next to a local record, or where a record opened by its
     * ID had its files downloaded; null when there is none ({@code whyNone} says why: the download failed).
     */
    static Result prepare(String source, String qtraceJson, Path tempDir, Path beside, String whyNone) {
        return prepare(source, qtraceJson, tempDir, beside, whyNone, false);
    }

    private static Result prepare(String source, String qtraceJson, Path tempDir, Path givenBeside, String whyNone, boolean fromSource) {
        Map<String, JsonObject> latest = new LinkedHashMap<>();
        inScope(qtraceJson, -1).forEach(r -> latest.put(name(r), r));
        List<String> verified = new ArrayList<>();
        Map<String, String> missing = new LinkedHashMap<>(), differs = new LinkedHashMap<>(), notCaptured = new LinkedHashMap<>();
        if (latest.isEmpty()) return new Result(verified, missing, differs, notCaptured, Map.of());

        boolean local = RecordSource.parse(source).local();
        // From the source alone: the folder next to a local record; a record opened by its ID has had nothing downloaded
        Path beside = fromSource ? (local ? ObjectClassifierFiles.companionDir(Path.of(source.strip())) : null) : givenBeside;
        if (fromSource && !local) whyNone = "its file was not downloaded from the workspace";
        Map<String, ObjectClassifierFiles.Check> checkOfVersion = new LinkedHashMap<>();
        for (JsonObject v : versions(qtraceJson)) {
            ObjectClassifierFiles.Check check;
            Path file = beside == null || !v.has("classifier_file") ? null
                : beside.resolve(Path.of(v.get("classifier_file").getAsString()).getFileName());
            if (beside == null && v.has("sha256")) {
                check = new ObjectClassifierFiles.Check(ObjectClassifierFiles.Status.MISSING, whyNone != null ? whyNone : "no folder to look in");
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
        writeProblems(tempDir, checkOfVersion);
        // An older version counts when a creation step needs it: an instruction runs it before the next version
        Map<String, ObjectClassifierFiles.Check> olderNeeded = new LinkedHashMap<>();
        for (ReplayStep st : neededCreations(qtraceJson)) {
            String k = st.objectClassifierFile().name() + "@" + st.objectClassifierFile().sha256();
            JsonObject last = latest.get(st.objectClassifierFile().name());
            ObjectClassifierFiles.Check c = checkOfVersion.get(k);
            if (last == null || key(last).equals(k) || c == null || c.status() == ObjectClassifierFiles.Status.OK) continue;
            olderNeeded.putIfAbsent(st.objectClassifierFile().name(), new ObjectClassifierFiles.Check(c.status(),
                "an earlier version an instruction runs (" + st.objectClassifierFile().sha256().substring(0, 12) + "): " + c.reason()));
        }
        List<JsonObject> okRecords = new ArrayList<>();
        for (JsonObject r : latest.values())
            if (checkOfVersion.get(key(r)).status() == ObjectClassifierFiles.Status.OK) okRecords.add(r);
        Map<String, String> embedded = new LinkedHashMap<>();
        for (JsonObject r : latest.values()) {
            ObjectClassifierFiles.Check c = checkOfVersion.get(key(r));
            if (c.status() == ObjectClassifierFiles.Status.OK && olderNeeded.containsKey(name(r))) c = olderNeeded.get(name(r));
            switch (c.status()) {
                case OK -> verified.add(name(r));
                case MISSING -> {
                    // A composite carries its components: the replay stays exact without the standalone file, unless a step runs it by name
                    String carrier = carrierOf(r, okRecords, qtraceJson);
                    if (carrier != null) embedded.put(name(r), carrier); else missing.put(name(r), c.reason());
                }
                case DIFFERS -> differs.put(name(r), c.reason());
                case NOT_CAPTURED -> notCaptured.put(name(r), c.reason());
            }
        }
        return new Result(verified, missing, differs, notCaptured, embedded);
    }

    /** The creation steps a replay of the whole record runs (each packet of a workflow played on its own too). */
    private static List<ReplayStep> neededCreations(String qtraceJson) {
        List<ReplayStep> out = new ArrayList<>();
        try {
            List<ReplayStep> steps = new ArrayList<>(QTraceReplayEngine.extractSteps(qtraceJson, -1));
            if (WorkflowSource.isWorkflow(qtraceJson))
                for (int p = 0; p < sessions(qtraceJson).size(); p++) steps.addAll(QTraceReplayEngine.extractSteps(qtraceJson, p));
            for (ReplayStep st : steps) if (st.createsObjectClassifier() && st.isExecutable()) out.add(st);
        } catch (RuntimeException e) {
            // not a record the engine can read: the check stays on the latest versions
        }
        return out;
    }

    /** Beside the verified copies: why each other version cannot be used, for the step that would need it. */
    private static void writeProblems(Path tempDir, Map<String, ObjectClassifierFiles.Check> checkOfVersion) {
        JsonObject problems = new JsonObject();
        checkOfVersion.forEach((k, c) -> {
            if (c.status() != ObjectClassifierFiles.Status.OK && !k.endsWith("@")) problems.addProperty(k, c.reason());
        });
        try {
            Path dir = tempDir.resolve(DIR);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(PROBLEMS_FILE), problems.toString());
        } catch (IOException e) {
            // the steps then say they have no verified copy, without the detail
        }
    }

    // ── the creation step ────────────────────────────────────────────────────

    /** The type a creation step is listed under: the family of the instruction that runs the classifier. */
    static final String STEP_TYPE = "Object Classifier";

    /** What a creation step is called; a name saved in several versions adds which one it is ("(v1/2)": the title column is narrow). */
    static final String STEP_TITLE = "Create object classifier";

    /** Where {@link #prepare} says why a version's copy cannot be used (beside the verified copies; no classifier file is named so: they all carry an @). */
    static final String PROBLEMS_FILE = "_problems.json";

    /** One recorded version of a classifier: the session that first lists it, and when it was saved (null: not said). */
    private record Version(JsonObject rec, int session, String name, String sha256, Instant savedAt) {
        String key() { return name + "@" + sha256; }
    }

    /** Every distinct version that has a fingerprint (only those can be created exactly), with the session that first lists it. */
    private static List<Version> fingerprinted(JsonArray sessions) {
        Map<String, Version> out = new LinkedHashMap<>();
        for (int i = 0; i < sessions.size(); i++)
            for (JsonObject rec : records(sessions.get(i))) {
                if (!rec.has("sha256") || !rec.get("sha256").isJsonPrimitive()) continue;
                Version v = new Version(rec, i, name(rec), rec.get("sha256").getAsString(), instant(rec, "saved_at"));
                out.putIfAbsent(v.key(), v);
            }
        return new ArrayList<>(out.values());
    }

    /**
     * Inserts the creation of each object classifier the scope saved, at its time among the instructions, plus the
     * classifiers an earlier session saved that an instruction of the scope runs (first: they were there before).
     * A creation stands before the first instruction done after it was saved, or before the first instruction with no
     * time that runs it. It is <em>needed</em> when an instruction after it runs it before any other version of that
     * name is created; only then does it run (and write the file if the project has not got it). One that nothing
     * runs stays listed, with nothing to run. Records written before the content was kept (no sha256) get no step:
     * nothing could be created exactly. The result is not numbered: the caller numbers the whole list.
     *
     * @param instructions the instructions of the scope, in replay order
     * @param times        when each was done (its last occurrence), null when not said
     * @param workflow     a workflow's packets can be played alone or in any order: a classifier saved in a later
     *                     packet still serves an instruction of this one when no earlier packet saved it
     */
    static List<ReplayStep> withCreations(JsonArray sessions, int sessionIndex, boolean workflow,
                                          List<ReplayStep> instructions, List<Instant> times) {
        List<Version> all = fingerprinted(sessions);
        if (all.isEmpty()) return instructions;
        int start = sessionIndex < 0 ? 0 : sessionIndex;
        int end = sessionIndex < 0 ? sessions.size() : Math.min(sessionIndex + 1, sessions.size());

        Map<String, Version> carried = new LinkedHashMap<>();
        for (Version v : all) if (v.session() < start) carried.put(v.name(), v);   // the last one before the scope
        if (workflow)
            for (Version v : all)
                if (v.session() >= end) carried.putIfAbsent(v.name(), v);         // or the first one a later packet saved
        List<Version> own = new ArrayList<>();
        for (Version v : all) if (v.session() >= start && v.session() < end) own.add(v);
        own.sort(java.util.Comparator.comparing(Version::savedAt, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));

        // Where each goes: before instruction k (k = size: after the last one)
        int n = instructions.size();
        List<List<Version>> before = new ArrayList<>();
        for (int k = 0; k <= n; k++) before.add(new ArrayList<>());
        before.get(0).addAll(carried.values());
        for (Version v : own) before.get(place(v, instructions, times)).add(v);

        List<Object> merged = new ArrayList<>();
        for (int k = 0; k <= n; k++) {
            merged.addAll(before.get(k));
            if (k < n) merged.add(instructions.get(k));
        }

        List<Object> kept = new ArrayList<>();
        Map<Version, ReplayStep> runBy = new java.util.IdentityHashMap<>();
        for (int i = 0; i < merged.size(); i++) {
            if (!(merged.get(i) instanceof Version v)) { kept.add(merged.get(i)); continue; }
            ReplayStep run = firstRun(merged, i, v.name());
            if (run == null && carried.containsValue(v) && !own.contains(v)) continue;   // brought in for nothing: left out
            if (run != null) runBy.put(v, run);
            kept.add(v);
        }

        Map<String, Integer> versionsOf = new LinkedHashMap<>();
        for (Object o : kept) if (o instanceof Version v) versionsOf.merge(v.name(), 1, Integer::sum);
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<ReplayStep> out = new ArrayList<>();
        for (Object o : kept) {
            if (o instanceof ReplayStep st) { out.add(st); continue; }
            Version v = (Version) o;
            int nth = seen.merge(v.name(), 1, Integer::sum), of = versionsOf.get(v.name());
            String title = STEP_TITLE + ": " + v.name() + (of > 1 ? " (v" + nth + "/" + of + ")" : "");
            if (title.length() > 60) title = title.substring(0, 60) + "…";
            ReplayStep run = runBy.get(v);
            JsonObject session = sessions.get(v.session()).getAsJsonObject();
            String sid = session.has("session_id") && session.get("session_id").isJsonPrimitive() ? session.get("session_id").getAsString() : null;
            boolean validated = run != null ? run.validated() : io.qtrace.StampIntegrity.isStamped(session);
            out.add(new ReplayStep(0, STEP_TYPE, title, run != null ? creationFragment(v.name(), v.sha256()) : "",
                validated, sid, false, new ReplayStep.ObjectClassifierFile(v.name(), v.sha256(), run != null)));
        }
        return out;
    }

    private static int place(Version v, List<ReplayStep> instructions, List<Instant> times) {
        if (v.savedAt() == null) return 0;
        for (int k = 0; k < instructions.size(); k++) {
            Instant at = k < times.size() ? times.get(k) : null;
            if (at != null ? at.isAfter(v.savedAt()) : runs(instructions.get(k), v.name())) return k;
        }
        return instructions.size();
    }

    /** The first instruction after {@code i} that runs that name, before any other version of it is created; null if none. */
    private static ReplayStep firstRun(List<Object> merged, int i, String name) {
        for (int j = i + 1; j < merged.size(); j++) {
            Object o = merged.get(j);
            if (o instanceof Version other && other.name().equals(name)) return null;
            if (o instanceof ReplayStep st && !st.skipped() && runs(st, name)) return st;
        }
        return null;
    }

    /** Whether an instruction runs the classifier by its name ({@code runObjectClassifier("name")}). */
    static boolean runs(ReplayStep step, String name) {
        String script = step.groovyFragment();
        return script != null && script.contains("runObjectClassifier")
            && (script.contains("\"" + name + "\"") || script.contains("'" + name + "'"));
    }

    /**
     * The script of a creation step. It puts the recorded file in the project only when the project does not hold
     * that exact file (same sha256): identical, nothing is written; different, the project's file is kept aside
     * ({@code .replaced-<time>}) and the recorded one written; absent, it is written. The bytes come from the verified
     * copy the preamble carries ({@link #copiesBlock}); without it the step fails and says why - the replay never
     * goes on with another classifier under that name. Its last value says what it did (the Player's note).
     */
    static String creationFragment(String name, String sha256) {
        String q = groovy(name);
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(STEP_TITLE).append(" '").append(name.replace("\n", " ")).append("' (sha256 ").append(sha256)
          .append("), only if the project does not already hold this exact file\n");
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            sb.append("throw new IllegalStateException('Object classifier \"").append(q)
              .append("\" cannot be created: its name is not a plain file name - the replay will not use another classifier.')\n");
            return sb.toString();
        }
        sb.append("def _ocName = '").append(q).append("'\n");
        sb.append("def _ocSha = '").append(groovy(sha256)).append("'\n");
        sb.append("def _ocKey = _ocName + '@' + _ocSha\n");
        sb.append("def _ocHex = { byte[] b -> java.security.MessageDigest.getInstance('SHA-256').digest(b).encodeHex().toString() }\n");
        sb.append("def _ocProject = getProject()\n");
        sb.append("if (_ocProject == null)\n");
        sb.append("    throw new IllegalStateException('No project open: object classifier \"' + _ocName + '\" cannot be created. Open a QuPath project, then replay.')\n");
        sb.append("def _ocDir = new File(_ocProject.getPath().toFile().parentFile, 'classifiers/object_classifiers')\n");
        sb.append("def _ocFile = new File(_ocDir, _ocName + '.json')\n");
        sb.append("def _ocNote\n");
        sb.append("if (_ocFile.isFile() && _ocHex(_ocFile.bytes) == _ocSha) {\n");
        // Nothing written: Core is told the replay used the project's own copy, so that the new trace holds it too
        sb.append("    try { io.qtrace.QTraceController.current().getLogger().objectClassifierFoundByReplay(_ocFile.toPath()) } catch (Throwable _ocOld) { }\n");
        sb.append("    _ocNote = 'already in the project (identical file, nothing written)'\n");
        sb.append("} else {\n");
        sb.append("    def _ocCopy = binding.hasVariable('_qtraceObjectClassifiers') ? _qtraceObjectClassifiers[_ocKey] : null\n");
        sb.append("    if (_ocCopy == null) {\n");
        sb.append("        def _ocWhy = (binding.hasVariable('_qtraceObjectClassifierProblems') ? _qtraceObjectClassifierProblems[_ocKey] : null) ?: 'the Player has no verified copy of its file'\n");
        sb.append("        throw new IllegalStateException('Object classifier \"' + _ocName + '\" cannot be created: ' + _ocWhy\n");
        sb.append("            + (_ocFile.exists() ? '. The project holds another file of that name, left as it is' : '. The project does not hold it')\n");
        sb.append("            + ' - the replay will not use another classifier.')\n");
        sb.append("    }\n");
        sb.append("    def _ocBytes = _ocCopy.decodeBase64()\n");
        sb.append("    if (_ocHex(_ocBytes) != _ocSha)\n");
        sb.append("        throw new IllegalStateException('Object classifier \"' + _ocName + '\" cannot be created: its copy does not match the recorded sha256 - the replay will not use another classifier.')\n");
        sb.append("    _ocDir.mkdirs()\n");
        sb.append("    def _ocReplaced = null\n");
        sb.append("    _ocNote = 'created from the record\\'s copy'\n");
        sb.append("    if (_ocFile.exists()) {\n");
        sb.append("        def _ocKept = new File(_ocDir, _ocName + '.json.replaced-' + System.currentTimeMillis())\n");
        sb.append("        if (!_ocFile.renameTo(_ocKept))\n");
        sb.append("            throw new IllegalStateException('Object classifier \"' + _ocName + '\": the project holds another file of that name and it could not be kept aside - nothing replaced.')\n");
        sb.append("        _ocReplaced = _ocKept.name\n");
        sb.append("        _ocNote = 'replaced: the project\\'s different file is kept as ' + _ocKept.name\n");
        sb.append("    }\n");
        // Core's folder watcher must take the write for a restore by the replay, not for the user saving a classifier
        sb.append("    try { io.qtrace.ObjectClassifierRestores.expect(_ocName, _ocSha, _ocReplaced) }\n");
        sb.append("    catch (Throwable _ocOld) { try { io.qtrace.ObjectClassifierRestores.expect(_ocName, _ocSha) } catch (Throwable _ocOlder) { } }\n");
        sb.append("    _ocFile.bytes = _ocBytes\n");
        // Recorded at once in the new trace (written as soon as the last instruction ends), as restored by the replay
        sb.append("    try { io.qtrace.QTraceController.current().getLogger().objectClassifierRestoredByReplay(_ocFile.toPath(), _ocReplaced) } catch (Throwable _ocOld) { }\n");
        sb.append("}\n");
        sb.append("println('[QTrace] Object classifier ' + _ocName + ': ' + _ocNote)\n");
        sb.append("_ocNote\n");
        return sb.toString();
    }

    /**
     * The preamble's part: the verified copy of each classifier version a creation step of the list may write, as
     * data (it writes nothing in the project), and why the others cannot be used. Then, as before, a warning for each
     * classifier saved before qTrace kept the content: nothing can create it exactly.
     */
    static String copiesBlock(String qtraceJson, int sessionIndex, Path classifierDir, List<ReplayStep> steps) {
        StringBuilder sb = new StringBuilder();
        Map<String, Version> byKey = new LinkedHashMap<>();
        for (Version v : fingerprinted(sessions(qtraceJson))) byKey.put(v.key(), v);
        Map<String, String> problems = problems(classifierDir);
        List<String> keys = new ArrayList<>();
        for (ReplayStep st : steps) {
            if (!st.createsObjectClassifier() || !st.isExecutable()) continue;
            String key = st.objectClassifierFile().name() + "@" + st.objectClassifierFile().sha256();
            if (!keys.contains(key)) keys.add(key);
        }
        if (!keys.isEmpty()) {
            sb.append("// Object classifiers: the verified copy of each version a creation step may write (data only)\n");
            sb.append("_qtraceObjectClassifiers = [:]\n");
            sb.append("_qtraceObjectClassifierProblems = [:]\n");
        }
        for (String key : keys) {
            Version v = byKey.get(key);
            byte[] bytes = v == null ? null : verifiedCopy(classifierDir, v);
            if (bytes == null) {
                String why = problems.getOrDefault(key, classifierDir == null
                    ? "the Player prepared no copy of the record's files" : "no verified copy of its file");
                sb.append("_qtraceObjectClassifierProblems['").append(groovy(key)).append("'] = '").append(groovy(why)).append("'\n");
                continue;
            }
            String b64 = Base64.getEncoder().encodeToString(bytes);
            // 60000-char chunks stay under the JVM's 65535-char string literal limit
            int chunk = 60000;
            sb.append("_qtraceObjectClassifiers['").append(groovy(key)).append("'] = \"").append(b64, 0, Math.min(chunk, b64.length())).append("\"");
            for (int pos = chunk; pos < b64.length(); pos += chunk)
                sb.append(" +\n    \"").append(b64, pos, Math.min(pos + chunk, b64.length())).append("\"");
            sb.append("\n");
        }
        if (!keys.isEmpty()) sb.append("\n");
        for (JsonObject rec : inScope(qtraceJson, sessionIndex)) {
            if (rec.has("sha256")) continue;
            sb.append("println('[QTrace] WARNING: object classifier \"").append(groovy(name(rec)))
              .append("\" cannot be restored (it was saved before qTrace kept the content of object classifiers)")
              .append(" - this replay will NOT use the recorded classifier');\n\n");
        }
        return sb.toString();
    }

    /** The staged copy of that version, read back and checked against its fingerprint; null when there is none that matches. */
    private static byte[] verifiedCopy(Path classifierDir, Version v) {
        if (classifierDir == null || !v.rec().has("classifier_file")) return null;
        Path file = classifierDir.resolve(DIR).resolve(Path.of(v.rec().get("classifier_file").getAsString()).getFileName());
        try {
            if (!Files.isRegularFile(file)) return null;
            byte[] bytes = Files.readAllBytes(file);
            return io.qtrace.chain.Hashing.sha256Hex(bytes).equals(v.sha256()) ? bytes : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** What {@link #prepare} wrote about the versions whose copy cannot be used, by name@sha256. */
    private static Map<String, String> problems(Path classifierDir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (classifierDir == null) return out;
        Path f = classifierDir.resolve(DIR).resolve(PROBLEMS_FILE);
        try {
            if (!Files.isRegularFile(f)) return out;
            JsonElement el = JsonParser.parseString(Files.readString(f));
            if (el.isJsonObject()) el.getAsJsonObject().entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        } catch (IOException | RuntimeException e) {
            // no reasons: the step says it has no verified copy
        }
        return out;
    }

    private static Instant instant(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        try {
            return Instant.parse(o.get(key).getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── a composite carries its components ───────────────────────────────────

    /**
     * The name of a restored composite that carries this classifier's rule inside it, when no step runs the
     * classifier by its own name (then its standalone file is not needed for an exact replay); else null.
     */
    private static String carrierOf(JsonObject standalone, List<JsonObject> restored, String qtraceJson) {
        if (!standalone.has("content") || !standalone.get("content").isJsonObject()) return null;
        if (runsByName(qtraceJson, name(standalone))) return null;
        JsonObject rule = standalone.getAsJsonObject("content");
        for (JsonObject c : restored) {
            if (!c.has("content") || !c.get("content").isJsonObject() || name(c).equals(name(standalone))) continue;
            if (carries(c.getAsJsonObject("content"), rule)) return name(c);
        }
        return null;
    }

    private static boolean carries(JsonObject composite, JsonObject rule) {
        if (!"composite".equals(composite.has("kind") ? composite.get("kind").getAsString() : "")) return false;
        for (JsonElement el : composite.getAsJsonArray("components")) {
            if (!el.isJsonObject()) continue;
            if (el.getAsJsonObject().equals(rule) || carries(el.getAsJsonObject(), rule)) return true;
        }
        return false;
    }

    /** Whether a recorded step runs the classifier by its name ({@code runObjectClassifier("name")}). */
    private static boolean runsByName(String qtraceJson, String name) {
        for (JsonElement s : sessions(qtraceJson)) {
            if (!s.isJsonObject() || !s.getAsJsonObject().has("steps") || !s.getAsJsonObject().get("steps").isJsonArray()) continue;
            for (JsonElement st : s.getAsJsonObject().getAsJsonArray("steps")) {
                if (!st.isJsonObject() || !st.getAsJsonObject().has("script_fragment")) continue;
                String script = st.getAsJsonObject().get("script_fragment").getAsString();
                if (script.contains("runObjectClassifier") && (script.contains("\"" + name + "\"") || script.contains("'" + name + "'"))) return true;
            }
        }
        return false;
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
