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

import io.qtrace.net.QTraceHttp;
import io.qtrace.QTraceController;
import io.qtrace.QTraceUpdater;
import io.qtrace.StampIntegrity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.player.ext.ExtensionHandlerRegistry;
import io.qtrace.player.ext.InstanSegHandler;
import io.qtrace.player.ext.QTraceExtensionHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a .qtrace JSON file (local or from the workspace) and generates
 * a self-contained Groovy replay script for QuPath.
 *
 * The classifier model files are downloaded separately from the workspace
 * and embedded inline in the generated script — the .qtrace itself no longer
 * carries the classifier JSON since v1.0.2.
 */
public class QTraceReplayEngine {

    // www.qtrace.ca (not the apex) — qtrace.ca 308-redirects to www.
    // Same override as the updater and the welcome module (local portal, test bench).
    private static final String BASE_URL = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");

    // Share links opened in this session: qtrace.ca resolves the token to its record once, then
    // every download of that record (classifiers…) carries the token.
    private static final Map<String, String> CERT_BY_TOKEN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> TOKEN_BY_CERT = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolves {@code source} to the raw JSON content of a .qtrace file.
     * Accepts: a local file path, a bare qtc_... ID, a /verify/qtc_... URL, or an anonymous
     * share link (/share/g/<token>). qtrace.ca answers only to who may read the record.
     */
    public static String downloadQtrace(String source) throws IOException, InterruptedException {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is blank");

        RecordSource src = RecordSource.parse(source);
        if (src.local()) return Files.readString(Path.of(source));

        String certId = src.certId();
        if (src.token() != null) {
            // Anonymous share link: its token names the record.
            String grant = fetchUrl(BASE_URL + "/api/grants/anonymous/" + src.token());
            certId = JsonParser.parseString(grant).getAsJsonObject()
                .getAsJsonObject("certBundle").get("certId").getAsString();
            CERT_BY_TOKEN.put(src.token(), certId);
            TOKEN_BY_CERT.put(certId, src.token());
        }
        return fetchUrl(RecordSource.contentUrl(BASE_URL, certId, "qtrace", TOKEN_BY_CERT.get(certId)));
    }

    /**
     * Outcome of {@link #downloadClassifiers}: which classifiers were written to disk, and
     * which failed along with a short reason — a caller (e.g. the Player's pre-check panel)
     * can report specifics instead of a generic "someone's missing" message.
     */
    public record DownloadResult(List<String> succeeded, Map<String, String> failed) {}

    /**
     * Downloads all classifiers for a given certId into {@code tempDir}.
     * Each classifier is saved as {@code tempDir/<name>.json}.
     *
     * A single classifier's download failing does not abort the others: the failure is
     * caught, recorded in the returned {@link DownloadResult#failed()} map, and the loop
     * moves on to the next entry. Only a failure to reach the classifier list itself
     * (the initial {@code /classifiers} fetch) still throws — there is nothing to iterate
     * over in that case.
     */
    public static DownloadResult downloadClassifiers(String certId, Path tempDir)
            throws IOException, InterruptedException {
        Files.createDirectories(tempDir);
        String apiUrl = RecordSource.contentUrl(BASE_URL, certId, "classifiers", TOKEN_BY_CERT.get(certId));
        String json = fetchUrl(apiUrl);

        JsonArray entries = JsonParser.parseString(json).getAsJsonArray();
        HttpClient client = newClient();
        List<String> succeeded = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        for (JsonElement el : entries) {
            JsonObject entry = el.getAsJsonObject();
            String name = entry.get("name").getAsString();
            String url  = entry.get("url").getAsString();
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
                HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() >= 300) {
                    failed.put(name, "HTTP " + resp.statusCode());
                    continue;
                }
                Files.write(tempDir.resolve(name + ".json"), resp.body());
                succeeded.add(name);
            } catch (IOException e) {
                failed.put(name, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed.put(name, "interrupted");
            }
        }
        return new DownloadResult(succeeded, failed);
    }

    /**
     * Names of every pixel classifier referenced across all sessions of a .qtrace, in
     * first-seen order (classifiers are identical across sessions — same source used by
     * {@link #buildPreamble}'s restoration loop). Used by the Player to know what to expect
     * from {@link #downloadClassifiers} before rendering its pre-check row.
     */
    public static List<String> extractClassifierNames(String qtraceJson) {
        JsonObject root = JsonParser.parseString(qtraceJson).getAsJsonObject();
        JsonArray sessions = root.has("sessions") ? root.getAsJsonArray("sessions") : new JsonArray();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (JsonElement el : sessions) {
            JsonObject session = el.getAsJsonObject();
            if (!session.has("pixel_classifiers")) continue;
            for (JsonElement clfEl : session.getAsJsonArray("pixel_classifiers")) {
                String name = getString(clfEl.getAsJsonObject(), "name");
                if (!name.isBlank()) names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * Generates a self-contained Groovy replay script from a .qtrace JSON string.
     *
     * @param qtraceJson   raw JSON content of the .qtrace file
     * @param classifierDir directory containing downloaded classifier JSON files,
     *                      or null to skip classifier restoration
     * @param sessionIndex  0-based session to replay, or -1 for all sessions
     */
    public static String generateGroovyScript(String qtraceJson, Path classifierDir, int sessionIndex) {
        return generateGroovyScript(qtraceJson, classifierDir, sessionIndex, false);
    }

    /** @param validatedOnly leave out instructions recorded only in unstamped sessions. */
    public static String generateGroovyScript(String qtraceJson, Path classifierDir, int sessionIndex,
                                              boolean validatedOnly) {
        JsonObject root = JsonParser.parseString(qtraceJson).getAsJsonObject();
        JsonArray sessions = root.getAsJsonArray("sessions");
        Map<String, String> recordedExtensions = collectRecordedExtensions(sessions);
        Map<String, String> fqnToSimple = collectFqnToSimple(sessions);
        List<QTraceExtensionHandler> activeHandlers =
            ExtensionHandlerRegistry.get().activePreambleHandlers(recordedExtensions.keySet());

        StringBuilder sb = new StringBuilder();
        sb.append(buildPreamble(root, sessions, sessionIndex, classifierDir, recordedExtensions, fqnToSimple, activeHandlers));

        // ── Steps — global dedup across all sessions (last occurrence wins) ──────
        List<ReplayStep> replaySteps = withoutSkipped(extractSteps(sessions, sessionIndex, fqnToSimple, activeHandlers));
        int kept = 0;
        for (ReplayStep step : replaySteps) {
            if (validatedOnly && !step.validated()) continue;
            appendStepBlock(sb, validatedOnly ? step.withIndex(++kept) : step);
        }
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Assembles a self-contained script from a preamble already evaluated by the Player's
     * {@link ReplayExecutor} and a caller-chosen subset of steps (e.g. only the rows still
     * checked in the Player's playlist), in the order given.
     *
     * Reuses the exact preamble text the run executed rather than regenerating one from the
     * .qtrace JSON, so "Export Code" reflects what actually ran, not a fresh reconstruction.
     */
    public static String assembleScript(String preamble, List<ReplayStep> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append(preamble);
        for (ReplayStep step : steps)
            if (!step.skipped()) appendStepBlock(sb, step);
        sb.append("\n");
        return sb.toString();
    }

    /**
     * The steps a replay runs: those the author took out of it ({@link ReplayStep#skipped})
     * are dropped and the rest renumbered from 1. {@link #extractSteps} keeps them, flagged,
     * for a Player that lists them greyed out.
     */
    public static List<ReplayStep> withoutSkipped(List<ReplayStep> steps) {
        List<ReplayStep> kept = new ArrayList<>();
        for (ReplayStep st : steps)
            if (!st.skipped()) kept.add(st.withIndex(kept.size() + 1));
        return kept;
    }

    private static void appendStepBlock(StringBuilder sb, ReplayStep step) {
        String label = step.title();
        if (!step.validated()) {
            String sid = step.sessionId() != null ? step.sessionId() : "?";
            sb.append("// [unstamped] session ").append(sid, 0, Math.min(8, sid.length()))
              .append(" — recorded without a stamp, not validated\n");
        }
        sb.append("// ── Step ").append(step.index()).append(": ").append(label).append("\n");
        sb.append("println('[QTrace] Step ").append(step.index()).append(" start: ").append(label.replace("'", "\\'")).append("')\n");
        sb.append("try {\n");
        for (String line : step.groovyFragment().split("\n", -1))
            sb.append("    ").append(line).append("\n");
        sb.append("    println('[QTrace] Step ").append(step.index()).append(" OK')\n");
        sb.append("} catch (Exception _e_step) {\n");
        sb.append("    println('[QTrace] Step ").append(step.index()).append(" FAILED: ' + _e_step.message)\n");
        sb.append("}\n");
    }

    /**
     * Generates everything a replay script needs before its step loop — header, imports,
     * extension check, FQN class-loading shims, extension-handler preambles, classifier
     * restoration blocks. Evaluated once per run by the Player's ReplayExecutor before
     * stepping through {@link #extractSteps}, and reused as-is inside {@link #generateGroovyScript}
     * for the full-script (Script Editor / CLI) path.
     */
    public static String buildPreambleScript(String qtraceJson, Path classifierDir, int sessionIndex) {
        JsonObject root = JsonParser.parseString(qtraceJson).getAsJsonObject();
        JsonArray sessions = root.getAsJsonArray("sessions");
        Map<String, String> recordedExtensions = collectRecordedExtensions(sessions);
        Map<String, String> fqnToSimple = collectFqnToSimple(sessions);
        List<QTraceExtensionHandler> activeHandlers =
            ExtensionHandlerRegistry.get().activePreambleHandlers(recordedExtensions.keySet());
        return buildPreamble(root, sessions, sessionIndex, classifierDir, recordedExtensions, fqnToSimple, activeHandlers);
    }

    private static String buildPreamble(JsonObject root, JsonArray sessions, int sessionIndex, Path classifierDir,
            Map<String, String> recordedExtensions, Map<String, String> fqnToSimple,
            List<QTraceExtensionHandler> activeHandlers) {
        JsonObject image = root.has("image") ? root.getAsJsonObject("image") : new JsonObject();

        StringBuilder sb = new StringBuilder();

        // ── Header ────────────────────────────────────────────────────────────
        sb.append("// ============================================================\n");
        sb.append("// QTrace Replay Script  v").append(QTraceController.VERSION).append("\n");
        sb.append("// Generated   : ").append(Instant.now()).append("\n");
        sb.append("// Image       : ").append(getString(image, "name")).append("\n");
        sb.append("// SHA-256     : ").append(getString(image, "sha256")).append("\n");
        sb.append("// Sessions    : ").append(sessions.size())
          .append(sessionIndex >= 0 ? " (replaying session " + sessionIndex + " only)" : " (all)").append("\n");

        int totalManual = 0;
        for (JsonElement el : sessions) {
            JsonObject s = el.getAsJsonObject();
            if (s.has("manual_annotations"))
                totalManual += s.get("manual_annotations").getAsInt();
        }
        if (totalManual > 0) {
            sb.append("// WARNING     : ").append(totalManual)
              .append(" manual annotation(s) not replayed (not scriptable)\n");
        }

        int totalMeasurementMaps = 0;
        for (JsonElement el : sessions) {
            JsonObject s = el.getAsJsonObject();
            if (s.has("measurement_maps_count"))
                totalMeasurementMaps += s.get("measurement_maps_count").getAsInt();
        }
        if (totalMeasurementMaps > 0) {
            sb.append("// WARNING     : ").append(totalMeasurementMaps)
              .append(" measurement map view(s) not replayed (display-only, not scriptable)\n");
        }
        // recordedExtensions collected by caller (shared with extractSteps)
        if (!recordedExtensions.isEmpty()) {
            sb.append("// Extensions at record time:\n");
            for (Map.Entry<String, String> e : recordedExtensions.entrySet())
                sb.append("//   ").append(e.getKey())
                  .append(e.getValue().isBlank() ? "" : " v" + e.getValue()).append("\n");
        }
        sb.append("// ============================================================\n\n");
        sb.append("import static qupath.lib.gui.scripting.QPEx.*\n");
        sb.append("import qupath.lib.objects.*\n");
        sb.append("import qupath.lib.objects.classes.*\n");
        sb.append("import qupath.lib.roi.*\n");
        sb.append("import qupath.lib.common.*\n");
        sb.append("import qupath.lib.measurements.*\n");
        sb.append("import qupath.lib.scripting.*\n");
        sb.append("import qupath.lib.images.servers.*\n");
        if (totalMeasurementMaps > 0) {
            sb.append("println('[QTrace] ⚠ WARNING: ").append(totalMeasurementMaps)
              .append(" measurement map view(s) not replayed — display-only, execute manually in QuPath')\n\n");
        }
        // fqnToSimple collected by caller (shared with extractSteps)
        // Emit runtime extension check against recorded list
        if (!recordedExtensions.isEmpty()) {
            sb.append("// ── Extension check ──────────────────────────────────────────────\n");
            sb.append("{ def _extClass = null\n");
            sb.append("  try { _extClass = this.class.classLoader.loadClass('qupath.lib.gui.extensions.QuPathExtension') } catch (Exception _ignored) {}\n");
            sb.append("  def _loaded = []\n");
            sb.append("  if (_extClass != null) {\n");
            sb.append("    try {\n");
            sb.append("      java.util.ServiceLoader.load(_extClass, this.class.classLoader).each { _loaded << it.name }\n");
            sb.append("    } catch (Exception _ignored) {}\n");
            sb.append("  }\n");
            for (Map.Entry<String, String> e : recordedExtensions.entrySet()) {
                String name = e.getKey().replace("'", "\\'");
                String ver  = e.getValue().replace("'", "\\'");
                sb.append("  if (!_loaded.any { it?.contains('").append(name).append("') })\n");
                sb.append("    println('[QTrace] MISSING EXTENSION: ").append(name);
                if (!ver.isBlank()) sb.append(" v").append(ver);
                sb.append(" — install via Extensions menu')\n");
            }
            sb.append("};\n\n");
        }
        // Emit try-wrapped loadClass per unique simple name — graceful if extension not installed.
        // Deliberately NOT "def <name> = null": a `def`-declared variable is local to the script
        // that declares it and vanishes once that evaluate() call returns. The Player runs the
        // preamble and each step as separate GroovyShell.evaluate() calls against a shared
        // Binding — only undeclared (bare) assignment writes through to that Binding and stays
        // visible to later calls. Harmless for the single full-script path too: within one script
        // an undeclared top-level var behaves the same as a local one.
        Set<String> emittedSimples = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : fqnToSimple.entrySet()) {
            if (emittedSimples.add(e.getValue())) {
                sb.append(e.getValue()).append(" = null;\n");
                sb.append("try { ").append(e.getValue())
                  .append(" = this.class.classLoader.loadClass('").append(e.getKey()).append("'); }")
                  .append(" catch (ClassNotFoundException _e_").append(e.getValue()).append(") {")
                  .append(" println('[QTrace] WARNING: ").append(e.getKey())
                  .append(" not found — install the required QuPath extension'); }\n");
            }
        }
        // Emit preambles from extension handlers that are active for this .qtrace (collected by caller)
        for (QTraceExtensionHandler h : activeHandlers)
            sb.append(h.buildPreambleGroovy()).append("\n");

        // ── Classifier restoration blocks ─────────────────────────────────────
        if (classifierDir != null) {
            for (JsonElement el : sessions) {
                JsonObject session = el.getAsJsonObject();
                if (!session.has("pixel_classifiers")) continue;
                for (JsonElement clfEl : session.getAsJsonArray("pixel_classifiers")) {
                    JsonObject clf = clfEl.getAsJsonObject();
                    String name = getString(clf, "name");
                    if (name.isBlank()) continue;
                    Path clfFile = classifierDir.resolve(name + ".json");
                    if (!Files.exists(clfFile)) {
                        sb.append("println('[QTrace] WARNING: pixel classifier \"").append(name.replace("'", "\\'"))
                          .append("\" could not be restored — download it manually or check your network, then re-run')\n\n");
                        continue;
                    }
                    try {
                        // Base64-encode the raw JSON to avoid string literal size limit (JVM: 65535 chars)
                        byte[] rawBytes = Files.readAllBytes(clfFile);
                        String b64 = java.util.Base64.getEncoder().encodeToString(rawBytes);
                        sb.append("// Restore classifier: ").append(name)
                          .append(" (").append(getString(clf, "classifier_type")).append(")\n");
                        // Write to project classifier dir so script_fragment calls like
                        // createAnnotationsFromPixelClassifier("NAME", ...) can find it by name
                        sb.append("{\n");
                        // Split b64 into 60000-char chunks to stay under JVM 65535 string literal limit
                        int CHUNK = 60000;
                        sb.append("  def _b64 = \"").append(b64, 0, Math.min(CHUNK, b64.length())).append("\"");
                        for (int pos = CHUNK; pos < b64.length(); pos += CHUNK) {
                            sb.append(" +\n      \"").append(b64, pos, Math.min(pos + CHUNK, b64.length())).append("\"");
                        }
                        sb.append("\n");
                        sb.append("  def _json = new String(java.util.Base64.getDecoder().decode(_b64), 'UTF-8')\n");
                        sb.append("  def _proj = getProject()\n");
                        sb.append("  if (_proj != null) {\n");
                        sb.append("    def _dir = new java.io.File(_proj.getPath().toFile().parentFile,\n");
                        sb.append("        'classifiers/pixel_classifiers')\n");
                        sb.append("    _dir.mkdirs()\n");
                        sb.append("    new java.io.File(_dir, '").append(name).append(".json').text = _json\n");
                        sb.append("    print('✓ Classifier ").append(name).append(" restored to project')\n");
                        sb.append("  } else {\n");
                        sb.append("    print('⚠ No project open — open a QuPath project before running')\n");
                        sb.append("  }\n");
                        sb.append("}.call();\n\n");
                    } catch (IOException e) {
                        sb.append("// WARNING: could not read classifier ").append(name)
                          .append(" from ").append(clfFile).append("\n\n");
                    }
                }
                break; // classifiers are identical across sessions — use first session only
            }
        }

        return sb.toString();
    }

    /**
     * Extracts the deduplicated, rewritten list of executable steps for a .qtrace —
     * the structured counterpart of the step loop inside {@link #generateGroovyScript}.
     * Used by the Player for real programmatic step-by-step execution.
     */
    public static List<ReplayStep> extractSteps(String qtraceJson, int sessionIndex) {
        return extractSteps(qtraceJson, sessionIndex, false);
    }

    /**
     * @param validatedOnly leave out instructions recorded only in unstamped sessions
     *                      (never validated by a stamp); the rest is renumbered from 1.
     */
    public static List<ReplayStep> extractSteps(String qtraceJson, int sessionIndex, boolean validatedOnly) {
        List<ReplayStep> all = extractStepsAll(qtraceJson, sessionIndex);
        if (!validatedOnly) return all;
        List<ReplayStep> kept = new ArrayList<>();
        for (ReplayStep st : all)
            if (st.validated()) kept.add(st.withIndex(kept.size() + 1));
        return kept;
    }

    private static List<ReplayStep> extractStepsAll(String qtraceJson, int sessionIndex) {
        JsonObject root = JsonParser.parseString(qtraceJson).getAsJsonObject();
        JsonArray sessions = root.getAsJsonArray("sessions");
        Map<String, String> recordedExtensions = collectRecordedExtensions(sessions);
        Map<String, String> fqnToSimple = collectFqnToSimple(sessions);
        List<QTraceExtensionHandler> activeHandlers =
            ExtensionHandlerRegistry.get().activePreambleHandlers(recordedExtensions.keySet());
        return extractSteps(sessions, sessionIndex, fqnToSimple, activeHandlers);
    }

    private static List<ReplayStep> extractSteps(JsonArray sessions, int sessionIndex,
            Map<String, String> fqnToSimple, List<QTraceExtensionHandler> activeHandlers) {
        int start = sessionIndex < 0 ? 0 : sessionIndex;
        int end   = sessionIndex < 0 ? sessions.size() : Math.min(sessionIndex + 1, sessions.size());

        // Validated = present in at least one stamped session of the whole file (a later stamp
        // covers earlier unstamped work it carries over), whatever range is being replayed.
        java.util.Set<String> validatedFrags = new java.util.HashSet<>();
        for (JsonElement el : sessions) {
            JsonObject session = el.getAsJsonObject();
            if (!StampIntegrity.isStamped(session)) continue;
            validatedFrags.addAll(scriptableFragments(session).keySet());
        }

        LinkedHashMap<String, String> seen = new LinkedHashMap<>();   // fragment → session id
        // Taken out of the replay by the author: the last occurrence in the range decides.
        java.util.Set<String> skippedFrags = new java.util.HashSet<>();
        // The name its step was recorded under ("Set image type", "Script: x.groovy"): the last occurrence's.
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = start; i < end; i++) {
            JsonObject session = sessions.get(i).getAsJsonObject();
            String sid = session.has("session_id") && !session.get("session_id").isJsonNull()
                ? session.get("session_id").getAsString() : null;
            names.putAll(fragmentNames(session));
            for (Map.Entry<String, Boolean> frag : scriptableFragments(session).entrySet()) {
                seen.remove(frag.getKey());
                seen.put(frag.getKey(), sid);
                if (frag.getValue()) skippedFrags.add(frag.getKey()); else skippedFrags.remove(frag.getKey());
            }
        }

        List<ReplayStep> result = new ArrayList<>();
        int stepNum = 0;

        // Class panel first: the classes the author created exist before anything uses them.
        for (int i = start; i < end; i++) {
            JsonObject session = sessions.get(i).getAsJsonObject();
            if (!session.has("class_list") || !session.get("class_list").isJsonArray()) continue;
            boolean stamped = StampIntegrity.isStamped(session);
            String sid = session.has("session_id") && !session.get("session_id").isJsonNull()
                ? session.get("session_id").getAsString() : null;
            for (JsonElement el : session.getAsJsonArray("class_list")) {
                JsonObject c = el.getAsJsonObject();
                if (c.has("scriptable") && !c.get("scriptable").getAsBoolean()) continue;
                if (getString(c, "name").isBlank()) continue;
                stepNum++;
                ReplayStep st = buildClassListStep(stepNum, c);
                result.add(new ReplayStep(st.index(), st.type(), st.title(), st.groovyFragment(), stamped, sid));
            }
        }

        for (Map.Entry<String, String> entry : seen.entrySet()) {
            String frag = entry.getKey();
            stepNum++;
            String emitted = simplifyQualifiedNames(frag, fqnToSimple);
            for (QTraceExtensionHandler h : activeHandlers)
                emitted = h.rewriteStepGroovy(emitted);
            // Listed under the name of its step, as on the portal's "Scripts executed"; a step
            // recorded without a name falls back on the first line of its script.
            String label = names.getOrDefault(frag, "").strip();
            if (label.isEmpty()) label = emitted.lines().map(String::strip).filter(l -> !l.isBlank()).findFirst().orElse("?");
            if (label.length() > 60) label = label.substring(0, 60) + "…";
            result.add(new ReplayStep(stepNum, ReplayStep.classify(emitted), label, emitted,
                                      validatedFrags.contains(frag), entry.getValue(), skippedFrags.contains(frag)));
        }

        for (int i = start; i < end; i++) {
            JsonObject session = sessions.get(i).getAsJsonObject();
            if (!session.has("display_settings")) continue;
            boolean stamped = StampIntegrity.isStamped(session);
            String sid = session.has("session_id") && !session.get("session_id").isJsonNull()
                ? session.get("session_id").getAsString() : null;
            for (JsonElement el : session.getAsJsonArray("display_settings")) {
                JsonObject ds = el.getAsJsonObject();
                if (ds.has("scriptable") && !ds.get("scriptable").getAsBoolean()) continue;
                stepNum++;
                ReplayStep st = buildDisplaySettingsStep(stepNum, ds);
                result.add(new ReplayStep(st.index(), st.type(), st.title(), st.groovyFragment(), stamped, sid));
            }
        }
        return result;
    }

    /** {@code replay_skip}: the author took this step out of the replay (see Core's ReplaySkip). */
    private static boolean isReplaySkipped(JsonObject step) {
        return step.has("replay_skip") && !step.get("replay_skip").isJsonNull() && step.get("replay_skip").getAsBoolean();
    }

    /**
     * Active scriptable fragments of one session, in the order of their last occurrence (same
     * filter as the replay itself) → whether the author took that occurrence out of the replay.
     */
    private static LinkedHashMap<String, Boolean> scriptableFragments(JsonObject session) {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<>();
        JsonArray steps = session.has("steps") && session.get("steps").isJsonArray()
            ? session.getAsJsonArray("steps") : new JsonArray();
        for (JsonElement stepEl : steps) {
            JsonObject step = stepEl.getAsJsonObject();
            if (!step.has("script_fragment") || step.get("script_fragment").isJsonNull()) continue;
            if (step.has("deleted") && step.get("deleted").getAsBoolean()) continue;
            if (!step.has("is_scriptable") || !step.get("is_scriptable").getAsBoolean()) continue;
            String frag = step.get("script_fragment").getAsString().strip();
            if (frag.isBlank()) continue;
            out.remove(frag);
            out.put(frag, isReplaySkipped(step));
        }
        return out;
    }

    /** The {@code command} of each active scriptable fragment of one session, at its last occurrence. */
    private static Map<String, String> fragmentNames(JsonObject session) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonArray steps = session.has("steps") && session.get("steps").isJsonArray()
            ? session.getAsJsonArray("steps") : new JsonArray();
        for (JsonElement stepEl : steps) {
            JsonObject step = stepEl.getAsJsonObject();
            if (!step.has("script_fragment") || step.get("script_fragment").isJsonNull()) continue;
            if (step.has("deleted") && step.get("deleted").getAsBoolean()) continue;
            if (!step.has("is_scriptable") || !step.get("is_scriptable").getAsBoolean()) continue;
            String frag = step.get("script_fragment").getAsString().strip();
            if (frag.isBlank()) continue;
            out.put(frag, getString(step, "command"));
        }
        return out;
    }

    /**
     * Turns one {@code class_list[]} entry into an executable {@link ReplayStep}: the class panel
     * (Add/Remove > Add class, Remove class…) never reaches QuPath's history workflow, so the
     * Groovy is synthesized from the record (see Core's ClassListRecord). The class itself is
     * created at once — later steps can use it; its entry in the panel is added on the FX
     * thread, and only when QuPath runs with its window.
     */
    private static ReplayStep buildClassListStep(int stepNum, JsonObject c) {
        String name = getString(c, "name");
        boolean remove = "remove".equals(getString(c, "action"));
        String quoted = "'" + name.replace("\\", "\\\\").replace("'", "\\'") + "'";
        boolean coloured = !remove && c.has("color_rgb") && !c.get("color_rgb").isJsonNull();

        StringBuilder sb = new StringBuilder();
        sb.append("def _qtraceClass = getPathClass(").append(quoted);
        if (coloured) sb.append(", ").append(c.get("color_rgb").getAsInt());
        sb.append(")\n");
        sb.append("if (getQuPath() != null) javafx.application.Platform.runLater {\n");
        sb.append("    def _qtraceClasses = getQuPath().getAvailablePathClasses()\n");
        if (remove) sb.append("    _qtraceClasses.remove(_qtraceClass)\n");
        else        sb.append("    if (!_qtraceClasses.contains(_qtraceClass)) _qtraceClasses.add(_qtraceClass)\n");
        sb.append("}\n");

        return new ReplayStep(stepNum, "Class list", (remove ? "Remove class — " : "Add class — ") + name, sb.toString());
    }

    /**
     * Turns one {@code display_settings[]} entry into a real, executable {@link ReplayStep} —
     * unlike {@link #extractMeasurementMapSteps}, this synthesizes Groovy from structured data
     * rather than replaying captured script text, since QuPath's Brightness & contrast dialog
     * never pushes anything to the history workflow (see DisplaySettingsRecord's javadoc for
     * why every field here still has a public scripting equivalent).
     */
    private static ReplayStep buildDisplaySettingsStep(int stepNum, JsonObject ds) {
        JsonArray channels = ds.has("channels") ? ds.getAsJsonArray("channels") : new JsonArray();
        double gamma = ds.has("gamma") ? ds.get("gamma").getAsDouble() : 1.0;
        boolean grayscale = ds.has("grayscale") && ds.get("grayscale").getAsBoolean();
        boolean invertBackground = ds.has("invert_background") && ds.get("invert_background").getAsBoolean();

        StringBuilder sb = new StringBuilder();
        sb.append("def _qtraceDisplay = getCurrentViewer().getImageDisplay()\n");
        for (JsonElement el : channels) {
            JsonObject ch = el.getAsJsonObject();
            String name = getString(ch, "name");
            double min = ch.has("min") ? ch.get("min").getAsDouble() : 0;
            double max = ch.has("max") ? ch.get("max").getAsDouble() : 0;
            boolean selected = ch.has("selected") && ch.get("selected").getAsBoolean();
            String escapedName = name.replace("\\", "\\\\").replace("'", "\\'");
            sb.append("_qtraceDisplay.availableChannels().findAll { it.getName() == '").append(escapedName)
              .append("' }.each { _ch ->\n");
            sb.append("    _qtraceDisplay.setMinMaxDisplay(_ch, ").append((float) min).append("f, ")
              .append((float) max).append("f)\n");
            sb.append("    _qtraceDisplay.setChannelSelected(_ch, ").append(selected).append(")\n");
            if (ch.has("color_rgb") && !ch.get("color_rgb").isJsonNull()) {
                int rgb = ch.get("color_rgb").getAsInt();
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                sb.append("    if (_ch instanceof qupath.lib.display.DirectServerChannelInfo) _ch.setLUTColor(")
                  .append(r).append(", ").append(g).append(", ").append(b).append(")\n");
            }
            sb.append("}\n");
        }
        sb.append("_qtraceDisplay.setUseGrayscaleLuts(").append(grayscale).append(")\n");
        sb.append("_qtraceDisplay.setUseInvertedBackground(").append(invertBackground).append(")\n");
        sb.append("getCurrentViewer().setGamma(").append(gamma).append(")\n");

        String title = String.format("Display settings — %d channel(s), gamma %s", channels.size(), gamma);
        return new ReplayStep(stepNum, "Display settings", title, sb.toString());
    }

    /**
     * Builds non-executable {@link ReplayStep} rows from a session's {@code measurement_maps[]}
     * entries — the "Measurement Maps" dialog view captured by {@code ActionLogger}/
     * {@code MeasurementMapRecord} in qtrace-core, display-only and never scriptable (see the
     * {@code measurement_maps_count} WARNING already emitted by {@link #buildPreamble}). Each
     * returned step has a blank {@code groovyFragment} — {@link ReplayStep#isExecutable()} is
     * false — so the Player can show it in the Instructions table without letting it participate
     * in Play/step execution.
     *
     * Appended by the Player after the scriptable steps from {@link #extractSteps} rather than
     * interleaved by time: a .qtrace has no field tying a measurement-map timestamp to a specific
     * step, so exact chronological interleaving isn't attempted here — entries are returned in
     * their own recorded order.
     */
    public static List<ReplayStep> extractMeasurementMapSteps(String qtraceJson, int sessionIndex) {
        JsonObject root = JsonParser.parseString(qtraceJson).getAsJsonObject();
        JsonArray sessions = root.has("sessions") ? root.getAsJsonArray("sessions") : new JsonArray();
        int start = sessionIndex < 0 ? 0 : sessionIndex;
        int end   = sessionIndex < 0 ? sessions.size() : Math.min(sessionIndex + 1, sessions.size());

        List<ReplayStep> result = new ArrayList<>();
        int stepNum = 0;
        for (int i = start; i < end; i++) {
            JsonObject session = sessions.get(i).getAsJsonObject();
            if (!session.has("measurement_maps")) continue;
            for (JsonElement el : session.getAsJsonArray("measurement_maps")) {
                JsonObject m = el.getAsJsonObject();
                String measurement = getString(m, "measurement");
                String colormap = getString(m, "colormap");
                double rangeMin = m.has("range_min") && !m.get("range_min").isJsonNull() ? m.get("range_min").getAsDouble() : 0;
                double rangeMax = m.has("range_max") && !m.get("range_max").isJsonNull() ? m.get("range_max").getAsDouble() : 0;
                stepNum++;
                String title = String.format("Update map: \"%s\" · %s · range %.2f–%.2f",
                    measurement, colormap, rangeMin, rangeMax);
                result.add(new ReplayStep(stepNum, "Measurement Map", title, ""));
            }
        }
        return result;
    }

    private static Map<String, String> collectRecordedExtensions(JsonArray sessions) {
        Map<String, String> recordedExtensions = new LinkedHashMap<>();
        for (JsonElement el : sessions) {
            JsonObject s = el.getAsJsonObject();
            if (!s.has("extensions")) continue;
            for (JsonElement ee : s.getAsJsonArray("extensions")) {
                JsonObject ej = ee.getAsJsonObject();
                String eName = ej.has("name") ? ej.get("name").getAsString() : "";
                String eVer  = ej.has("version") ? ej.get("version").getAsString() : "";
                if (!eName.isBlank()) recordedExtensions.putIfAbsent(eName, eVer);
            }
        }
        return recordedExtensions;
    }

    /**
     * Replaces each fully-qualified extension class name by its simple name — the variable the
     * preamble loads it into, so that a missing extension is a warning and not a compile error.
     * An {@code import} line is left as written: {@code import qupath.imagej.tools.IJTools}
     * turned into {@code import IJTools} no longer compiles, and with its import in place the
     * simple name used in the rest of the script resolves to the class itself.
     */
    static String simplifyQualifiedNames(String fragment, Map<String, String> fqnToSimple) {
        if (fqnToSimple.isEmpty()) return fragment;
        StringBuilder out = new StringBuilder(fragment.length());
        for (String line : fragment.split("\n", -1)) {
            if (out.length() > 0) out.append('\n');
            if (!IMPORT_LINE.matcher(line).lookingAt())
                for (Map.Entry<String, String> e : fqnToSimple.entrySet())
                    line = line.replace(e.getKey(), e.getValue());
            out.append(line);
        }
        return out.toString();
    }

    private static final Pattern IMPORT_LINE = Pattern.compile("\\s*import\\s");

    private static Map<String, String> collectFqnToSimple(JsonArray sessions) {
        Map<String, String> fqnToSimple = new LinkedHashMap<>();
        Pattern fqnPat = Pattern.compile("qupath\\.(?!lib\\.)(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)+([A-Z][a-zA-Z0-9_]*)");
        for (JsonElement el : sessions) {
            JsonObject s = el.getAsJsonObject();
            JsonArray steps = s.has("steps") ? s.getAsJsonArray("steps") : new JsonArray();
            for (JsonElement se : steps) {
                JsonObject step = se.getAsJsonObject();
                if (!step.has("script_fragment")) continue;
                String fragText = step.get("script_fragment").getAsString();
                Matcher m = fqnPat.matcher(fragText);
                while (m.find()) fqnToSimple.put(m.group(), m.group(1));
            }
        }
        return fqnToSimple;
    }

    // ── Certified session ────────────────────────────────────────────────────

    /**
     * Index of the session a certificate covers — the last stamped one — or -1 if none is.
     * A certificate's .qtrace carries the image's whole history (earlier sessions, unstamped
     * work, autosaves after the stamp), but the qtc_… ID certifies only this session: it is
     * what the portal's "Scripts executed" shows, and what the Player replays by default.
     */
    public static int certifiedSessionIndex(String qtraceJson) {
        JsonObject root = JsonParser.parseString(qtraceJson).getAsJsonObject();
        JsonArray sessions = root.has("sessions") && root.get("sessions").isJsonArray()
            ? root.getAsJsonArray("sessions") : new JsonArray();
        for (int i = sessions.size() - 1; i >= 0; i--)
            if (StampIntegrity.isStamped(sessions.get(i).getAsJsonObject())) return i;
        return -1;
    }

    /** Whether {@code source} names a record on qtrace.ca (qtc_… or qtb_… ID, or a link) rather than a local .qtrace. */
    public static boolean isCertificateSource(String source) {
        return RecordSource.isRecordId(source) || (source != null && source.startsWith("http"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The record's ID; for a share link, known once {@link #downloadQtrace} has resolved it. */
    static String extractCertId(String source) {
        RecordSource src = RecordSource.parse(source);
        if (src.token() != null) return CERT_BY_TOKEN.get(src.token());
        // A local path is returned as is, as before: callers only keep a qtc_ value.
        return src.local() ? source : src.certId();
    }

    /** The licence, or a basic account's token; a Core published before basic accounts has only the licence. */
    private static String bearer() {
        try {
            return QTraceUpdater.bearer();
        } catch (LinkageError e) {
            return QTraceUpdater.licenseJwt();
        }
    }

    private static String fetchUrl(String url) throws IOException, InterruptedException {
        return fetchUrl(url, 5);
    }

    /**
     * GET as text. Redirects are followed by hand: a record download answers with a redirect to
     * a signed storage URL, and the licence presented to qtrace.ca must not travel there.
     */
    private static String fetchUrl(String url, int redirectsLeft) throws IOException, InterruptedException {
        HttpClient client = QTraceHttp.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest.Builder req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET();
        // qtrace.ca hands a record's content only to who may read it: say who is asking.
        String jwt = RecordSource.sendsLicence(BASE_URL, url) ? bearer() : null;
        if (jwt != null) req.header("Authorization", "Bearer " + jwt);
        HttpResponse<String> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status >= 300 && status < 400) {
            String location = resp.headers().firstValue("Location").orElse(null);
            if (location == null || redirectsLeft <= 0) throw new IOException("HTTP " + status + " from " + url);
            return fetchUrl(URI.create(url).resolve(location).toString(), redirectsLeft - 1);
        }
        if (status >= 300) {
            String refusal = RecordSource.refusal(status);
            throw new IOException(refusal != null ? refusal : "HTTP " + status + " from " + url + ": " + resp.body());
        }
        return resp.body();
    }

    private static HttpClient newClient() {
        return QTraceHttp.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL) // never an https → http downgrade
            .build();
    }

    // ── Pre-flight info ───────────────────────────────────────────────────────

    public record PreflightInfo(
        String imageName,
        String projectName,
        String qupathVersion,
        Map<String, String> requiredExtensions,
        Map<String, String> modelPaths
    ) {}

    public static PreflightInfo extractPreflightInfo(String qtraceJson) {
        JsonObject root = JsonParser.parseString(qtraceJson).getAsJsonObject();
        JsonObject image = root.has("image") ? root.getAsJsonObject("image") : new JsonObject();
        JsonArray sessions = root.has("sessions") ? root.getAsJsonArray("sessions") : new JsonArray();

        String imageName = getString(image, "name");
        String projectName = "";
        String qupathVersion = "";
        if (!sessions.isEmpty()) {
            JsonObject first = sessions.get(0).getAsJsonObject();
            if (first.has("project"))
                projectName = getString(first.getAsJsonObject("project"), "name");
            qupathVersion = getString(first, "qupath_version");
        }

        Map<String, String> extensions = new LinkedHashMap<>();
        for (JsonElement el : sessions) {
            JsonObject s = el.getAsJsonObject();
            if (!s.has("extensions")) continue;
            for (JsonElement ee : s.getAsJsonArray("extensions")) {
                JsonObject ej = ee.getAsJsonObject();
                String eName = getString(ej, "name");
                String eVer  = getString(ej, "version");
                if (!eName.isBlank()) extensions.putIfAbsent(eName, eVer);
            }
        }

        // Extract InstanSeg model base names from script fragments
        java.util.List<String> allFragments = new java.util.ArrayList<>();
        for (JsonElement el : sessions) {
            JsonObject s = el.getAsJsonObject();
            JsonArray steps = s.has("steps") ? s.getAsJsonArray("steps") : new JsonArray();
            for (JsonElement se : steps) {
                JsonObject step = se.getAsJsonObject();
                // A model only an instruction taken out of the replay needs is not required.
                if (step.has("script_fragment") && !isReplaySkipped(step))
                    allFragments.add(step.get("script_fragment").getAsString());
            }
        }
        Map<String, String> modelPaths = InstanSegHandler.extractModelPaths(allFragments);

        return new PreflightInfo(imageName, projectName, qupathVersion, extensions, modelPaths);
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }
}
