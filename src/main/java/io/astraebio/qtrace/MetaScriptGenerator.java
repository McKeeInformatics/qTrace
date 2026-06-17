package io.astraebio.qtrace;

import com.google.gson.JsonObject;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 3 — Generates a deduplicated Groovy replay script.
 *
 * Source of truth: {@link ActionLogger#getCapturedSteps()} — the enriched JSON list
 * that includes retroactively-captured pre-qTrace steps AND dynamically updated
 * annotation fragments (name, class, color, description, locked).
 * Steps marked {@code "deleted": true} are filtered out so that annotations the
 * user drew and then deleted never appear in the replay.
 *
 * Deduplication: keeps only the LAST occurrence of each content-identical step,
 * collapsing repeated setImageType / script runs while preserving the final,
 * authoritative state of every annotation.
 */
public class MetaScriptGenerator {

    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final ActionLogger logger;

    public MetaScriptGenerator(ActionLogger logger) {
        this.logger = logger;
    }

    public Path generate(Path outputDir) throws IOException {
        ImageData<BufferedImage> imageData = logger.getCurrentImageData();
        if (imageData == null) throw new IllegalStateException("No active image.");

        // Active (non-deleted) captured steps
        List<JsonObject> active = logger.getCapturedSteps().stream()
            .filter(j -> !j.has("deleted") || !j.get("deleted").getAsBoolean())
            .collect(Collectors.toList());

        List<JsonObject> deduped = deduplicateJson(active);

        int rawCount = imageData.getHistoryWorkflow().getSteps().size();
        int dropped  = active.size() - deduped.size();

        String imageName = imageData.getServer().getMetadata().getName();
        String hash      = logger.getImageHash();
        String now       = Instant.now().toString();
        String tsShort   = TS_FMT.format(Instant.now());

        StringBuilder sb = new StringBuilder();

        // ── Provenance header ────────────────────────────────────────────────
        sb.append("// ============================================================\n");
        sb.append("// QTrace Meta-Script  v").append(QTraceController.VERSION).append("\n");
        sb.append("// ============================================================\n");
        sb.append("// Generated   : ").append(now).append("\n");
        sb.append("// Image       : ").append(imageName).append("\n");
        String projectPathStr = null;
        try {
            var proj = logger.getQuPath().getProject();
            if (proj != null && proj.getPath() != null) {
                projectPathStr = proj.getPath().toAbsolutePath().toString();
                sb.append("// Project     : ").append(projectPathStr).append("\n");
            }
        } catch (Exception ignored) {}
        sb.append("// SHA-256     : ").append(hash != null ? hash : "(hash pending)").append("\n");
        sb.append("// Steps (raw) : ").append(rawCount).append("\n");
        sb.append("// Steps (dedup): ").append(deduped.size());
        if (dropped > 0) sb.append("  (").append(dropped).append(" duplicate(s) suppressed)");
        sb.append("\n");
        sb.append("// Manual ann  : ").append(logger.getManualAnnotationCount()).append("\n");

        // ── Alignment block (v0.5.21) ────────────────────────────────────────
        AlignmentRecord align = logger.getAlignmentRecord();
        if (align != null) {
            double[] m = align.matrix;
            sb.append("// ─────────────────────────────────────────────────────────\n");
            sb.append("// IMAGE ALIGNMENT (").append(align.transformType)
              .append(" via ").append(align.captureSource).append(")\n");
            sb.append("// Moving image   : ").append(align.movingImageName).append("\n");
            sb.append("// Reference image: ").append(align.referenceImageName).append("\n");
            sb.append("// Detected at    : ").append(align.detectedAt).append("\n");
            sb.append("// Detected by    : ").append(align.detectedBy).append("\n");
            sb.append("// Matrix layout  : ").append(align.matrixLayout).append("\n");
            sb.append(String.format(
                "// Matrix         : [%.6f, %.6f, %.6f, %.6f, %.6f, %.6f]\n",
                m[0], m[1], m[2], m[3], m[4], m[5]));
            sb.append(String.format(
                "// ScaleX=%.4f  ScaleY=%.4f  TransX=%.4f  TransY=%.4f\n",
                align.scaleX(), align.scaleY(), align.translateX(), align.translateY()));
            if (align.warpyFile != null) {
                sb.append("// Warpy file     : ").append(align.warpyFile).append("\n");
                sb.append("// Source ID      : ").append(align.warpySourceId)
                  .append("  Target ID: ").append(align.warpyTargetId).append("\n");
            }

            if ("AffineServer".equals(align.captureSource)) {
                sb.append("// NOTE: Persisted in .qpproj. To re-apply:\n");
                sb.append(String.format(
                    "//   def _t = new java.awt.geom.AffineTransform" +
                    "(%.6f,%.6f,%.6f,%.6f,%.6f,%.6f)\n",
                    m[0], m[1], m[2], m[3], m[4], m[5]));
                sb.append("//   // ImageServers.buildAffineTransformServer(getCurrentServer(), _t)\n");
            } else {
                // Warpy alignment — include paste-ready matrix + Groovy automation block
                // imglib2_rowmajor: [m00, m01, m02, m10, m11, m12] → displayed as 2 rows × 3 cols
                String row1 = String.format("%.5f, %.5f, %.5f", m[0], m[1], m[2]);
                String row2 = String.format("%.5f, %.5f, %.5f", m[3], m[4], m[5]);
                sb.append("//\n");
                sb.append("// To re-apply this alignment on another machine:\n");
                sb.append("//   1. Open both images in a QuPath project:\n");
                sb.append("//        Moving   : ").append(align.movingImageName).append("\n");
                sb.append("//        Reference: ").append(align.referenceImageName).append("\n");
                sb.append("//   2. Open  Analyse > Interactive Image Combiner Warpy\n");
                sb.append("//   3. Select the reference image in the Warpy dialog\n");
                sb.append("//   4. Paste into 'Current affine transform being displayed':\n");
                sb.append("//        ").append(row1).append("\n");
                sb.append("//        ").append(row2).append("\n");
                sb.append("//   5. Click Update\n");
                sb.append("//\n");
                sb.append("// ── Groovy automation block ────────────────────────────────\n");
                sb.append("// Run in the QuPath Script Editor while the Warpy dialog is\n");
                sb.append("// already open and the reference image is already selected.\n");
                sb.append("// If Warpy is not open, manual steps above are printed instead.\n");
                sb.append("{\n");
                sb.append("    def _mat = \"").append(row1).append("\\n").append(row2).append("\"\n");
                sb.append("    boolean _ok = false\n");
                sb.append("    for (def _w : javafx.stage.Window.getWindows()) {\n");
                sb.append("        if (!(_w instanceof javafx.stage.Stage) || !_w.showing) continue\n");
                sb.append("        if (!_w.title?.toLowerCase()?.contains('warpy')) continue\n");
                sb.append("        def _ta = _w.scene?.root?.lookup('.text-area')\n");
                sb.append("        if (!(_ta instanceof javafx.scene.control.TextArea)) continue\n");
                sb.append("        _ta.text = _mat\n");
                sb.append("        def _btn = _w.scene.root.lookupAll('.button').find {\n");
                sb.append("            it instanceof javafx.scene.control.Button &&\n");
                sb.append("            it.text?.toLowerCase()?.contains('updat')\n");
                sb.append("        }\n");
                sb.append("        if (_btn) { javafx.application.Platform.runLater({ _btn.fire() }); _ok = true }\n");
                sb.append("    }\n");
                sb.append("    if (!_ok) {\n");
                sb.append("        println '[QTrace] Warpy not open — follow manual steps in the comments above.'\n");
                sb.append("        println '[QTrace]   Row 1: ").append(row1).append("'\n");
                sb.append("        println '[QTrace]   Row 2: ").append(row2).append("'\n");
                sb.append("    }\n");
                sb.append("}\n");
                sb.append("// ── End alignment replay ──────────────────────────────────\n");
            }
            sb.append("// ─────────────────────────────────────────────────────────\n");
        }

        sb.append("// ============================================================\n\n");

        sb.append("import static qupath.lib.gui.scripting.QPEx.*\n\n");

        // ── One block per deduplicated step ──────────────────────────────────
        Map<String, ClassifierRecord> classifiers = logger.getKnownClassifiers();
        Set<String> restoredClassifiers = new LinkedHashSet<>();

        for (int i = 0; i < deduped.size(); i++) {
            JsonObject step = deduped.get(i);
            String stepName = step.has("command") ? step.get("command").getAsString() : "Unknown";

            // ── Inject classifier restore block before first step that references it
            if (step.has("script_fragment") && !step.get("script_fragment").isJsonNull()) {
                String frag = step.get("script_fragment").getAsString();
                for (Map.Entry<String, ClassifierRecord> entry : classifiers.entrySet()) {
                    String clfName = entry.getKey();
                    if (!restoredClassifiers.contains(clfName)
                            && frag.contains("\"" + clfName + "\"")) {
                        sb.append(buildClassifierRestoreBlock(entry.getValue()));
                        restoredClassifiers.add(clfName);
                    }
                }
            }

            sb.append("// ── Step ").append(i + 1).append(": ").append(stepName).append(" ──\n");

            if (step.has("script_fragment") && !step.get("script_fragment").isJsonNull()) {
                String src = step.get("script_fragment").getAsString();
                if (!src.isBlank()) {
                    if (hasAbsolutePath(src))
                        sb.append("// ⚠  Absolute path — update before replaying on another machine\n");
                    sb.append(src.strip()).append("\n");
                }
            } else {
                sb.append("// (non-scriptable — parameters below)\n");
                if (step.has("parameters")) {
                    step.getAsJsonObject("parameters").entrySet().forEach(e ->
                        sb.append("//   ").append(e.getKey())
                          .append(" = ").append(e.getValue().getAsString()).append("\n"));
                }
            }
            sb.append("\n");
        }

        // ── Write file ────────────────────────────────────────────────────────
        Files.createDirectories(outputDir);
        String base    = imageName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path   outFile = outputDir.resolve("replay_" + base + "_" + tsShort + ".groovy");
        Files.writeString(outFile, sb.toString());
        return outFile;
    }

    // ── Deduplication ────────────────────────────────────────────────────────

    private static List<JsonObject> deduplicateJson(List<JsonObject> steps) {
        List<String> fps = steps.stream()
            .map(MetaScriptGenerator::fingerprintJson)
            .collect(Collectors.toList());

        List<JsonObject> result = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            boolean appearsLater = false;
            for (int j = i + 1; j < steps.size(); j++) {
                if (fps.get(i).equals(fps.get(j))) { appearsLater = true; break; }
            }
            if (!appearsLater) result.add(steps.get(i));
        }
        return result;
    }

    private static String fingerprintJson(JsonObject j) {
        String name = j.has("command") ? j.get("command").getAsString() : "";
        if (j.has("script_fragment") && !j.get("script_fragment").isJsonNull()) {
            String src = j.get("script_fragment").getAsString();
            return name + "\0script\0" + normalize(src);
        }
        if (j.has("parameters")) {
            return name + "\0params\0" + j.getAsJsonObject("parameters").toString();
        }
        return name + "\0\0";
    }

    private static String normalize(String src) {
        if (src == null) return "";
        return src.strip().replaceAll(";+\\s*$", "");
    }

    private static boolean hasAbsolutePath(String src) {
        if (src.matches("(?s).*\"[A-Za-z]:[/\\\\].*")) return true;
        return src.contains("\"/home/") || src.contains("\"/Users/");
    }

    // ── Pixel classifier restore block ───────────────────────────────────────

    private static String buildClassifierRestoreBlock(ClassifierRecord clf) {
        String safe = clf.name.replaceAll("[^a-zA-Z0-9_]", "_");
        String b64  = Base64.getEncoder().encodeToString(
            clf.jsonContent.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        sb.append("// ── Restore pixel classifier: ").append(clf.name).append(" ──\n");
        if (clf.tpcFilePath != null)
            sb.append("// TPC file     : ").append(clf.tpcFilePath).append("\n");
        sb.append("// Type         : ").append(clf.classifierType);
        if (clf.resolutionUm > 0)
            sb.append(" | Resolution: ").append(String.format("%.4g", clf.resolutionUm)).append(" µm");
        if (clf.outputType != null)
            sb.append(" | Output: ").append(clf.outputType);
        sb.append("\n");
        if (clf.classes != null && clf.classes.size() > 0)
            sb.append("// Classes      : ").append(clf.classes).append("\n");
        sb.append("// Training     : ").append(clf.trainingAnnotationIds.size()).append(" region(s)");
        if (clf.trainingGeojsonFile != null)
            sb.append("  →  ").append(clf.trainingGeojsonFile);
        sb.append("\n");
        sb.append("// SHA-256      : ").append(clf.sha256, 0, 16).append("...\n");
        sb.append("// The trained model JSON is embedded below (Base64).\n");
        sb.append("// It will be written to project/classifiers/pixel_classifiers/ on replay.\n");
        sb.append("try {\n");
        // JVM string literal limit is 65535 chars — split b64 into 60000-char chunks
        int CHUNK = 60000;
        if (b64.length() <= CHUNK) {
            sb.append("    def _b64_").append(safe).append(" = \"").append(b64).append("\"\n");
        } else {
            sb.append("    def _b64_").append(safe).append(" = \"")
              .append(b64, 0, CHUNK).append("\"");
            int pos = CHUNK;
            while (pos < b64.length()) {
                int end = Math.min(pos + CHUNK, b64.length());
                sb.append(" +\n        \"").append(b64, pos, end).append("\"");
                pos = end;
            }
            sb.append("\n");
        }
        sb.append("    def _json_").append(safe)
          .append(" = new String(java.util.Base64.getDecoder().decode(_b64_").append(safe)
          .append("), \"UTF-8\")\n");
        sb.append("    def _dir_").append(safe)
          .append(" = getProject().getPath().getParent()\n")
          .append("        .resolve(\"classifiers\").resolve(\"pixel_classifiers\")\n");
        sb.append("    java.nio.file.Files.createDirectories(_dir_").append(safe).append(")\n");
        sb.append("    java.nio.file.Files.writeString(_dir_").append(safe)
          .append(".resolve(\"").append(clf.name).append(".json\"), _json_").append(safe).append(")\n");
        sb.append("    println(\"[QTrace] Classifier '").append(clf.name).append("' restored.\")\n");
        sb.append("} catch (Exception _e_").append(safe).append(") {\n");
        sb.append("    println(\"[QTrace] WARNING: classifier restore failed — \" + _e_")
          .append(safe).append(".getMessage())\n");
        sb.append("    println(\"[QTrace]   Manual: copy ").append(clf.name)
          .append(".json to project/classifiers/pixel_classifiers/\")\n");
        sb.append("}\n\n");
        return sb.toString();
    }
}
