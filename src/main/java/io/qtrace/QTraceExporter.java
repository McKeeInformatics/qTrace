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

import com.google.gson.*;
import qupath.lib.images.ImageData;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.WorkflowStep;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Writes the incremental .qtrace JSON passport (format 2.0).
 *
 * Structure:
 *   root.qtrace_format = "2.0"
 *   root.image         — written once on first export, never overwritten
 *   root.sessions[]    — one entry appended per export
 *
 * Each session captures the full provenance for that user/machine/timestamp:
 *   steps, alignment, pixel_classifiers, cell_intensity_classifications,
 *   annotations (with per-author breakdown), validation stamp, git hash.
 */
public class QTraceExporter {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    @SuppressWarnings("unused")
    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private final ActionLogger    logger;
    private final String          gitHash;
    private final ValidationStamp stamp;
    private       JsonArray        extensions = null;

    public QTraceExporter(ActionLogger logger, String gitHash, ValidationStamp stamp) {
        this.logger  = logger;
        this.gitHash = gitHash;
        this.stamp   = stamp;
    }

    public void setExtensions(JsonArray extensions) {
        this.extensions = extensions;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Appends a session to the existing .qtrace, or creates it if absent.
     * @return absolute path of the .qtrace file
     */
    public Path export(Path outputDir) throws IOException {
        ImageData<BufferedImage> imageData = logger.getCurrentImageData();
        if (imageData == null) throw new IllegalStateException("No active image.");

        String imageName = imageData.getServer().getMetadata().getName();
        Files.createDirectories(outputDir);
        String base    = imageName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path   outFile = outputDir.resolve(base + ".qtrace");

        JsonObject session = buildSession(imageData, outputDir, imageName);

        JsonObject root;
        if (Files.exists(outFile)) {
            try {
                root = JsonParser.parseString(Files.readString(outFile)).getAsJsonObject();
                if (!root.has("sessions") || !root.get("sessions").isJsonArray())
                    root = buildFreshRoot(imageData);
            } catch (Exception e) {
                root = buildFreshRoot(imageData);
            }
        } else {
            root = buildFreshRoot(imageData);
        }

        // DAG v1: chain this commit to the previous one (last existing session_id).
        JsonArray existingSessions = root.getAsJsonArray("sessions");
        if (existingSessions.size() > 0) {
            JsonObject prev = existingSessions.get(existingSessions.size() - 1).getAsJsonObject();
            if (prev.has("session_id"))
                session.addProperty("parent_session_id", prev.get("session_id").getAsString());
        }
        existingSessions.add(session);

        // Write or preserve workflow status
        if (stamp != null && stamp.statusLabel() != null) {
            root.addProperty("status_index", stamp.statusIndex());
            root.addProperty("status",       stamp.statusLabel());
        }

        Files.writeString(outFile, GSON.toJson(root));
        return outFile;
    }

    /** Appends one row to master_validation_log.csv. */
    public Path appendToMasterCsv(Path outputDir) throws IOException {
        Path csvPath = outputDir.resolve("master_validation_log.csv");
        boolean isNew = !Files.exists(csvPath);

        Map<String, ClassifierRecord> classifiers = logger.getKnownClassifiers();
        String clfNames  = classifiers.values().stream().map(c -> c.name).collect(Collectors.joining("|"));
        String clfTypes  = classifiers.values().stream().map(c -> c.classifierType).collect(Collectors.joining("|"));
        String clfHashes = classifiers.values().stream()
            .map(c -> c.sha256.length() >= 16 ? c.sha256.substring(0, 16) : c.sha256)
            .collect(Collectors.joining("|"));
        String nTraining = classifiers.values().stream()
            .map(c -> String.valueOf(c.trainingAnnotationIds.size()))
            .collect(Collectors.joining("|"));
        String fidelity  = stamp != null ? stamp.classifierFidelity() : "N/A";

        ImageData<BufferedImage> imageData = logger.getCurrentImageData();
        String imageName = imageData != null
            ? imageData.getServer().getMetadata().getName() : "(unknown)";

        StringBuilder sb = new StringBuilder();
        if (isNew) {
            sb.append("image_name,generated,qtrace_version,git_hash,user,machine,validated_by,")
              .append("validation_timestamp,scope,confidence,classifier_fidelity,")
              .append("classifier_names,classifier_types,classifier_sha256,n_training_regions,notes\n");
        }
        sb.append(csv(imageName)).append(",")
          .append(Instant.now()).append(",")
          .append(QTraceController.VERSION).append(",")
          .append(csv(gitHash != null ? gitHash : "")).append(",")
          .append(csv(System.getProperty("user.name", "unknown"))).append(",")
          .append(csv(getHostname())).append(",")
          .append(stamp != null ? csv(stamp.validator())  : "").append(",")
          .append(stamp != null ? stamp.timestamp()       : "").append(",")
          .append(stamp != null ? csv(stamp.scope())      : "").append(",")
          .append(stamp != null ? csv(stamp.confidence()) : "").append(",")
          .append(csv(fidelity)).append(",")
          .append(csv(clfNames)).append(",")
          .append(csv(clfTypes)).append(",")
          .append(csv(clfHashes)).append(",")
          .append(csv(nTraining)).append(",")
          .append(stamp != null ? csv(stamp.notes()) : "").append("\n");

        Files.createDirectories(outputDir);
        try (FileWriter fw = new FileWriter(csvPath.toFile(), !isNew)) {
            fw.write(sb.toString());
        }
        return csvPath;
    }

    // ── Root document ─────────────────────────────────────────────────────────

    private JsonObject buildFreshRoot(ImageData<BufferedImage> imageData) {
        JsonObject root = new JsonObject();
        root.addProperty("qtrace_format", "2.0");

        JsonObject image = new JsonObject();
        image.addProperty("name",     imageData.getServer().getMetadata().getName());
        image.addProperty("sha256",   logger.getImageHash());
        image.addProperty("type",     imageData.getImageType().toString());
        image.addProperty("width",    imageData.getServer().getWidth());
        image.addProperty("height",   imageData.getServer().getHeight());
        image.addProperty("channels", imageData.getServer().nChannels());
        var uris = imageData.getServer().getURIs();
        if (!uris.isEmpty()) image.addProperty("uri", uris.iterator().next().toString());
        root.add("image", image);

        root.add("sessions", new JsonArray());
        return root;
    }

    // ── Session builder ───────────────────────────────────────────────────────

    private JsonObject buildSession(ImageData<BufferedImage> imageData,
                                     Path outputDir, String imageName) throws IOException {
        JsonObject session = new JsonObject();

        session.addProperty("session_id",     UUID.randomUUID().toString());
        // Authoritative contributor (one commit = one author): Compliance license → config → OS login.
        session.addProperty("user",           QTraceController.currentContributor());
        session.addProperty("os_user",        System.getProperty("user.name", "unknown"));  // forensic
        session.addProperty("machine",        getHostname());
        session.addProperty("branch",         "main");  // DAG v1: single linear branch
        session.addProperty("qtrace_version", QTraceController.VERSION);
        session.addProperty("exported_at",    Instant.now().toString());
        try {
            session.addProperty("qupath_version", qupath.lib.common.GeneralTools.getVersion());
        } catch (Exception ignored) {
            session.addProperty("qupath_version", "(unknown)");
        }

        // Validation stamp
        if (stamp != null) {
            JsonObject val = new JsonObject();
            val.addProperty("validator",           stamp.validator());
            val.addProperty("timestamp",           stamp.timestamp().toString());
            val.addProperty("scope",               stamp.scope());
            val.addProperty("confidence",          stamp.confidence());
            val.addProperty("notes",               stamp.notes());
            val.addProperty("classifier_fidelity", stamp.classifierFidelity());
            if (stamp.signature()      != null) val.addProperty("signature",       stamp.signature());
            if (stamp.validatorKeyPub() != null) val.addProperty("validatorKeyPub", stamp.validatorKeyPub());
            if (stamp.imageHash()       != null) val.addProperty("imageHash",       stamp.imageHash());
            if (stamp.qpdataSha256()    != null) val.addProperty("qpdata_sha256",   stamp.qpdataSha256());
            if (stamp.caseId()          != null && !stamp.caseId().isBlank())
                                                 val.addProperty("case_id",         stamp.caseId());
            if (stamp.statusLabel()     != null) val.addProperty("statusLabel",     stamp.statusLabel());
            val.addProperty("signing_meaning", ValidationStamp.SIGNING_MEANING);
            session.add("validation", val);
        } else {
            session.add("validation", JsonNull.INSTANCE);
        }

        // Workflow steps
        List<WorkflowStep> rawSteps = imageData.getHistoryWorkflow().getSteps();
        JsonArray stepsArr = new JsonArray();
        for (JsonObject captured : logger.getCapturedSteps()) {
            if (!captured.has("deleted") || !captured.get("deleted").getAsBoolean())
                stepsArr.add(captured);
        }
        session.addProperty("steps_raw",         rawSteps.size());
        session.addProperty("steps_captured",     stepsArr.size());
        session.addProperty("manual_annotations", logger.getManualAnnotationCount());
        session.add("steps", stepsArr);

        // Contributions: categorized summary of this commit's actions, attributed to its
        // single author. Pre-tracking steps (prior contributor's inherited state) excluded.
        session.add("contributions", buildContributions(stepsArr));

        // Git
        JsonObject git = new JsonObject();
        git.addProperty("commit",   gitHash);
        git.addProperty("repo_dir", outputDir.toString());
        session.add("git", git);

        // Project context
        JsonObject projectObj = new JsonObject();
        try {
            var qupath  = logger.getQuPath();
            var project = qupath.getProject();
            if (project != null) {
                projectObj.addProperty("name", project.getName());
                if (project.getPath() != null)
                    projectObj.addProperty("path", project.getPath().toAbsolutePath().toString());
                JsonArray imgList = new JsonArray();
                for (var entry : project.getImageList()) imgList.add(entry.getImageName());
                projectObj.add("images", imgList);
                projectObj.addProperty("image_count", imgList.size());
            } else {
                projectObj.addProperty("name", "(no project)");
                projectObj.add("images", new JsonArray());
                projectObj.addProperty("image_count", 0);
            }
        } catch (Exception ex) {
            projectObj.addProperty("error", ex.getMessage());
        }
        session.add("project", projectObj);

        // Alignment
        session.add("alignment", buildAlignmentObject(logger.getAlignmentRecord()));

        // Pixel classifiers
        session.add("pixel_classifiers", buildClassifiersArray());

        // Object classifiers
        session.add("object_classifiers", buildObjectClassifiersArray());

        // Cell intensity classifications
        session.add("cell_intensity_classifications", buildCellIntensityArray());

        // Annotations with per-author attribution
        session.add("annotations", buildAnnotationsObject(imageData, outputDir, imageName));

        // Manual detection corrections (audit-only — not replayable)
        session.add("manual_detection_corrections", buildDetectionCorrectionsArray());

        // Loaded QuPath extensions at export time
        if (extensions != null && extensions.size() > 0)
            session.add("extensions", extensions);

        return session;
    }

    /**
     * Categorized summary of a commit's actions, attributed to its single author.
     * Pre-tracking steps (inherited from a prior contributor) are excluded so the
     * graph node shows only what this contributor actually did.
     */
    private JsonObject buildContributions(JsonArray stepsArr) {
        JsonObject contrib = new JsonObject();
        contrib.addProperty("contributor", QTraceController.currentContributor());
        Map<String, Integer> actions = new LinkedHashMap<>();
        for (JsonElement el : stepsArr) {
            JsonObject step = el.getAsJsonObject();
            if (step.has("pre_tracking") && step.get("pre_tracking").getAsBoolean()) continue;
            String cmd = step.has("command") ? step.get("command").getAsString() : "";
            actions.merge(categorizeAction(cmd), 1, Integer::sum);
        }
        JsonObject actionsObj = new JsonObject();
        actions.forEach(actionsObj::addProperty);
        contrib.add("actions", actionsObj);
        return contrib;
    }

    /** Maps a QuPath workflow command name to a coarse action category for the graph. */
    private static String categorizeAction(String command) {
        String c = (command == null ? "" : command.toLowerCase());
        if (c.startsWith("manual annotation") || c.contains("annotation")) return "annotation";
        if (c.contains("pixel") && c.contains("classif"))                  return "pixel classification";
        if (c.contains("cell") && c.contains("detection"))                 return "cell detection";
        if (c.contains("classif"))                                         return "classification";
        if (c.contains("segment"))                                         return "segmentation";
        if (c.contains("align") || c.contains("warpy"))                    return "alignment";
        if (c.contains("threshold"))                                       return "thresholding";
        if (c.contains("image type"))                                      return "setup";
        return (command == null || command.isBlank()) ? "other" : command;
    }

    // ── Section builders ──────────────────────────────────────────────────────

    private JsonObject buildAlignmentObject(AlignmentRecord align) {
        JsonObject alignObj = new JsonObject();
        if (align != null) {
            alignObj.addProperty("applied",              true);
            alignObj.addProperty("capture_source",       align.captureSource);
            alignObj.addProperty("detected_at",          align.detectedAt.toString());
            alignObj.addProperty("detected_by",          align.detectedBy);
            alignObj.addProperty("transform_type",       align.transformType);
            alignObj.addProperty("matrix_layout",        align.matrixLayout);
            alignObj.addProperty("moving_image_hash",    align.movingImageHash);
            alignObj.addProperty("moving_image_name",    align.movingImageName);
            alignObj.addProperty("moving_image_uri",     align.movingImageUri);
            alignObj.addProperty("reference_image_name", align.referenceImageName);
            alignObj.addProperty("reference_image_uri",  align.referenceImageUri);
            JsonArray matrixArr = new JsonArray();
            for (double v : align.matrix) matrixArr.add(v);
            alignObj.add("matrix", matrixArr);
            alignObj.addProperty("scale_x",     align.scaleX());
            alignObj.addProperty("scale_y",     align.scaleY());
            alignObj.addProperty("translate_x", align.translateX());
            alignObj.addProperty("translate_y", align.translateY());
            alignObj.addProperty("shear_x",     align.shearX());
            alignObj.addProperty("shear_y",     align.shearY());
            if (align.warpyFile != null) {
                alignObj.addProperty("warpy_file",      align.warpyFile);
                alignObj.addProperty("warpy_source_id", align.warpySourceId);
                alignObj.addProperty("warpy_target_id", align.warpyTargetId);
            }
            if ("AffineServer".equals(align.captureSource)) {
                alignObj.addProperty("landmarks_note",
                    "Landmarks are transient in QuPath — not stored post-Apply. " +
                    "Place Point annotations before alignment for a visual audit trail.");
            }
        } else {
            alignObj.addProperty("applied", false);
        }
        return alignObj;
    }

    private JsonArray buildClassifiersArray() {
        JsonArray arr = new JsonArray();
        for (ClassifierRecord clf : logger.getKnownClassifiers().values()) {
            JsonObject co = new JsonObject();
            co.addProperty("name",                    clf.name);
            co.addProperty("saved_at",                clf.savedAt.toString());
            co.addProperty("saved_by_user",           clf.savedByUser);
            String imgShort = (clf.imageHashAtSave != null && clf.imageHashAtSave.length() >= 16)
                ? clf.imageHashAtSave.substring(0, 16) + "..." : clf.imageHashAtSave;
            co.addProperty("image_sha256_at_save",    imgShort);
            co.addProperty("sha256",                  clf.sha256);
            co.addProperty("git_hash",                clf.gitHash);
            co.addProperty("classifier_type",         clf.classifierType);
            co.addProperty("output_type",             clf.outputType);
            co.addProperty("resolution_um",           clf.resolutionUm);
            co.add("features",                        clf.features);
            co.addProperty("n_channels",              clf.channels.size());
            co.add("channels",                        clf.channels);
            co.add("scales",                          clf.scales);
            co.addProperty("local_normalization",       clf.localNormType  != null ? clf.localNormType  : "");
            co.addProperty("local_normalization_scale", clf.localNormScale);
            co.add("advanced_params",                   clf.advancedParams);
            co.add("classes",                           clf.classes);
            co.addProperty("n_training_regions",      clf.trainingAnnotationIds.size());
            co.addProperty("training_geojson",        clf.trainingGeojsonFile);
            co.addProperty("tpc_file",                clf.tpcFilePath);
            co.addProperty("fidelity",                clf.fidelity().name());
            co.addProperty("modified_after_training", clf.modifiedAfterTraining);
            if (clf.appliedAtStepOrder >= 0) co.addProperty("applied_at_step", clf.appliedAtStepOrder);
            if (clf.objectsCreated     >= 0) co.addProperty("objects_created",  clf.objectsCreated);
            arr.add(co);
        }
        return arr;
    }

    private JsonArray buildObjectClassifiersArray() {
        JsonArray arr = new JsonArray();
        for (JsonObject obj : logger.getKnownObjectClassifiers()) arr.add(obj);
        return arr;
    }

    private JsonArray buildCellIntensityArray() {
        JsonArray arr = new JsonArray();
        for (CellIntensityRecord cic : logger.getCellIntensityRecords().values()) {
            JsonObject cio = new JsonObject();
            cio.addProperty("measurement", cic.measurement);
            if (cic.thresholds.length >= 1) cio.addProperty("threshold_1plus", cic.thresholds[0]);
            if (cic.thresholds.length >= 2) cio.addProperty("threshold_2plus", cic.thresholds[1]);
            if (cic.thresholds.length >= 3) cio.addProperty("threshold_3plus", cic.thresholds[2]);
            JsonArray tArr = new JsonArray();
            for (double t : cic.thresholds) tArr.add(t);
            cio.add("thresholds",  tArr);
            cio.addProperty("applied_at", cic.appliedAt.toString());
            cio.addProperty("applied_by", cic.appliedBy);
            arr.add(cio);
        }
        return arr;
    }

    private JsonArray buildDetectionCorrectionsArray() {
        JsonArray arr = new JsonArray();
        for (JsonObject rec : logger.getManualDetectionCorrections()) arr.add(rec);
        return arr;
    }

    private JsonObject buildAnnotationsObject(ImageData<BufferedImage> imageData,
                                               Path outputDir, String imageName) throws IOException {
        Collection<PathObject> allAnnotations = imageData.getHierarchy().getAnnotationObjects();
        Set<UUID> sessionIds = logger.getSessionAnnotationIds();
        JsonObject annObj = new JsonObject();
        annObj.addProperty("total",             allAnnotations.size());
        annObj.addProperty("added_in_session",  (int) allAnnotations.stream()
                .filter(a -> sessionIds.contains(a.getID())).count());

        // Aggregate counts
        Map<String, Integer> byClass  = new LinkedHashMap<>();
        Map<String, Integer> byAuthor = new LinkedHashMap<>();
        JsonArray details = new JsonArray();

        for (PathObject ann : allAnnotations) {
            String cls    = ann.getPathClass() != null
                ? ann.getPathClass().getName() : "(unclassified)";
            String author = ActionLogger.retrieveAnnotationMeta(ann, "qtrace.author");
            if (author == null || author.isBlank()) author = "unknown";
            String capturedAt = ActionLogger.retrieveAnnotationMeta(ann, "qtrace.captured_at");

            byClass.merge(cls, 1, Integer::sum);
            byAuthor.merge(author, 1, Integer::sum);

            JsonObject d = new JsonObject();
            d.addProperty("uuid",        ann.getID().toString());
            d.addProperty("name",        ann.getName() != null ? ann.getName() : "");
            d.addProperty("class",       cls);
            d.addProperty("author",      author);
            if (capturedAt != null)      d.addProperty("captured_at", capturedAt);

            // Description (free-text from "Set annotation properties" dialog)
            String desc = getAnnotationDescription(ann);
            if (desc != null && !desc.isBlank()) d.addProperty("description", desc);

            // Color — object-level override first, then PathClass color
            Integer colorRgb = getObjectColor(ann);
            if (colorRgb == null && ann.getPathClass() != null)
                colorRgb = getObjectColor(ann.getPathClass());
            if (colorRgb != null)
                d.addProperty("color", String.format("#%06X", colorRgb & 0xFFFFFF));

            // Locked state
            d.addProperty("locked", ann.isLocked());

            // ROI geometry
            ROI roi = ann.getROI();
            if (roi != null) {
                d.addProperty("roi_type",     roi.getRoiName());
                d.addProperty("centroid_x",   roi.getCentroidX());
                d.addProperty("centroid_y",   roi.getCentroidY());
                d.addProperty("bounds_x",     roi.getBoundsX());
                d.addProperty("bounds_y",     roi.getBoundsY());
                d.addProperty("bounds_width",  roi.getBoundsWidth());
                d.addProperty("bounds_height", roi.getBoundsHeight());
                double area = roi.getArea();
                if (area > 0)  d.addProperty("area_px2",  area);
                double length = roi.getLength();
                if (length > 0) d.addProperty("length_px", length);
                // Pixel calibration for µm values
                try {
                    var cal = imageData.getServer().getPixelCalibration();
                    double pw = cal.getPixelWidthMicrons();
                    double ph = cal.getPixelHeightMicrons();
                    if (!Double.isNaN(pw) && pw > 0) {
                        if (area   > 0) d.addProperty("area_um2",  area   * pw * ph);
                        if (length > 0) d.addProperty("length_um", length * pw);
                    }
                } catch (Exception ignored) {}
                // Plane (z-slice, time point)
                d.addProperty("z", roi.getZ());
                d.addProperty("t", roi.getT());
                // Vertex count for polygons / polylines / point lists
                int nPts = roi.getNumPoints();
                if (nPts > 0) d.addProperty("num_points", nPts);
                // For lines: explicit start/end; for point annotations: full coordinate list
                String roiName = roi.getRoiName();
                if ("Line".equals(roiName) || "Points".equals(roiName)
                 || "Polygon".equals(roiName) || "Polyline".equals(roiName)
                 || "Geometry".equals(roiName)) {
                    var pts = roi.getAllPoints();
                    JsonArray ptsArr = new JsonArray();
                    for (var p : pts) {
                        JsonObject pt = new JsonObject();
                        pt.addProperty("x", p.getX());
                        pt.addProperty("y", p.getY());
                        ptsArr.add(pt);
                    }
                    d.add("points", ptsArr);
                }
            }
            details.add(d);
        }

        JsonObject byClassObj = new JsonObject();
        byClass.forEach(byClassObj::addProperty);
        annObj.add("by_class", byClassObj);

        JsonObject byAuthorObj = new JsonObject();
        byAuthor.forEach(byAuthorObj::addProperty);
        annObj.add("by_author", byAuthorObj);

        annObj.add("details", details);

        // GeoJSON with qtrace:author injected into each feature's properties
        String geoFile = exportAnnotationsWithAuthors(allAnnotations, QTraceConfig.get().getTrainingDir(), imageName);
        annObj.addProperty("geojson_file", geoFile != null ? geoFile : "");

        return annObj;
    }

    private String exportAnnotationsWithAuthors(Collection<PathObject> annotations,
                                                  Path outputDir, String imageName) throws IOException {
        if (annotations.isEmpty()) return null;

        String base  = imageName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String fname = base + "_annotations.geojson";

        com.google.gson.Gson baseGson = GsonTools.getInstance(false);
        JsonObject fc = new JsonObject();
        fc.addProperty("type", "FeatureCollection");
        JsonArray features = new JsonArray();

        for (PathObject ann : annotations) {
            try {
                JsonObject feature = JsonParser.parseString(baseGson.toJson(ann)).getAsJsonObject();
                JsonObject props;
                if (feature.has("properties") && feature.get("properties").isJsonObject()) {
                    props = feature.getAsJsonObject("properties");
                } else {
                    props = new JsonObject();
                    feature.add("properties", props);
                }
                String author = ActionLogger.retrieveAnnotationMeta(ann, "qtrace.author");
                String captAt = ActionLogger.retrieveAnnotationMeta(ann, "qtrace.captured_at");
                props.addProperty("qtrace:author", author != null ? author : "unknown");
                props.addProperty("qtrace:uuid",   ann.getID().toString());
                if (captAt != null) props.addProperty("qtrace:captured_at", captAt);
                features.add(feature);
            } catch (Exception ignored) {}
        }
        fc.add("features", features);

        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve(fname), GSON.toJson(fc));
        return fname;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String getHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // PathObject.getDescription() lives on PathAnnotationObject in QuPath 0.5.1 — access via reflection
    private static String getAnnotationDescription(PathObject obj) {
        try {
            var m = obj.getClass().getMethod("getDescription");
            Object val = m.invoke(obj);
            return val != null ? val.toString() : null;
        } catch (Exception ignored) { return null; }
    }

    // getColorRGB() was renamed/removed between QuPath 0.5.x and 0.7.x — try both names
    private static Integer getObjectColor(Object obj) {
        for (String name : new String[]{"getColorRGB", "getColor"}) {
            try {
                var m = obj.getClass().getMethod(name);
                Object val = m.invoke(obj);
                return val instanceof Integer i ? i : null;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
