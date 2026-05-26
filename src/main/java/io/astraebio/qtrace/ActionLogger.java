package io.astraebio.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.lib.images.servers.AffineTransformImageServer;
import java.awt.geom.AffineTransform;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.io.GsonTools;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.plugins.workflow.ScriptableWorkflowStep;
import qupath.lib.plugins.workflow.Workflow;
import qupath.lib.plugins.workflow.WorkflowListener;
import qupath.lib.plugins.workflow.WorkflowStep;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Phase 2 — Records every QuPath WorkflowStep as structured JSON.
 *
 * Lifecycle:
 *   attach(imageData)  — called by QTraceController on image open
 *   detach()           — called on image close / panel reset
 *
 * Outputs consumed by Phase 3 (MetaScriptGenerator):
 *   getCapturedSteps()       — ordered list of serialized WorkflowStep JSON
 *   getImageHash()           — SHA-256 of the source image file
 *   getCurrentImageData()    — live ImageData reference
 *   exportManualAnnotations  — GeoJSON file for manual corrections
 */
public class ActionLogger implements WorkflowListener {

    private final QuPathGUI qupath;
    private final QTracePanel panel;

    private final List<JsonObject> capturedSteps = new ArrayList<>();
    private int lastKnownStepCount = 0;
    private int manualAnnotationCount = 0;

    private volatile String  imageHash     = null;
    private volatile boolean scriptRunning = false;
    private volatile long    attachTime    = 0;

    private ImageData<BufferedImage> currentImageData = null;

    // Pixel classifier provenance (Phase 7) ──────────────────────────────────
    private final Map<String, ClassifierRecord> knownClassifiers = new ConcurrentHashMap<>();
    private volatile Thread watcherThread    = null;
    private volatile Thread objClassifierWatcherThread = null;
    private final Map<String, Long>    objClassifierWarnedAt  = new ConcurrentHashMap<>();
    private final List<JsonObject>     knownObjectClassifiers = new ArrayList<>();

    private static final java.util.regex.Pattern CLASSIFIER_NAME_PATTERN =
        java.util.regex.Pattern.compile("^\\d{8}-[A-Z0-9]+-\\w+-.*$");

    // Alignment provenance (v0.5.19 / v0.5.20) ──────────────────────────────
    private volatile AlignmentRecord currentAlignment       = null;
    private volatile Thread          alignmentWatcherThread = null;

    // Cell intensity classifications ─────────────────────────────────────────
    private final Map<String, CellIntensityRecord> cellIntensityRecords = new LinkedHashMap<>();

    // Pre-existing step count — set once at attach(), used by the panel to
    // distinguish history captured retroactively from new steps in this session.
    private int preExistingStepCount = 0;

    // Warpy extension support (v0.5.20 / v0.5.21) ────────────────────────────
    private volatile TextArea             warpyHookedTextArea    = null;
    private volatile Thread               warpyFileWatcherThread = null;
    // Button hooks — capture fires only on Save/Create/Update/Copy click
    private final List<Button>              warpyHookedButtons = new ArrayList<>();
    private       EventHandler<ActionEvent> warpyButtonHandler = null; // set in constructor

    // Manual annotation dedup — UUID → index in capturedSteps
    private final Map<UUID, Integer> annotationStepIndex = new HashMap<>();
    // UUIDs of annotations that already existed when qTrace attached — never re-capture these
    private final Set<UUID> snapshotAnnotationIds = new HashSet<>();
    // Script fragments of deleted annotations — filtered out by MetaScriptGenerator
    private final Set<String> deletedFragments = new HashSet<>();

    private final PathObjectHierarchyListener hierarchyListener = event -> {
        var type      = event.getEventType();
        boolean ready = System.currentTimeMillis() - attachTime >= 800;
        if (!ready) return;

        Collection<PathObject> changed = event.getChangedObjects();

        // New manual annotation drawn
        if (type == PathObjectHierarchyEvent.HierarchyEventType.ADDED && !scriptRunning) {
            for (PathObject obj : changed) {
                if (obj.isAnnotation()) captureManualAnnotation(obj);
            }
        }

        // Annotation deleted — remove it from the meta-script
        if (type == PathObjectHierarchyEvent.HierarchyEventType.REMOVED) {
            for (PathObject obj : changed) {
                if (obj.isAnnotation()) onAnnotationRemoved(obj);
            }
            if (!knownClassifiers.isEmpty()) checkTrainingIntegrity(changed);
        }

        // Annotation properties changed (name, class, color, description, locked)
        if (type == PathObjectHierarchyEvent.HierarchyEventType.OTHER_STRUCTURE_CHANGE && !scriptRunning) {
            for (PathObject obj : changed) {
                if (obj.isAnnotation()) refreshAnnotationCapture(obj);
            }
            if (!knownClassifiers.isEmpty()) checkTrainingIntegrity(changed);
        }
    };

    public ActionLogger(QuPathGUI qupath, QTracePanel panel) {
        this.qupath = qupath;
        this.panel  = panel;
        this.warpyButtonHandler = event -> Platform.runLater(() -> {
            String btnText = (event.getSource() instanceof Button b) ? b.getText() : "?";
            panel.log("[Alignment/Warpy] Button '" + btnText + "' clicked — capturing matrix...");
            if (warpyHookedTextArea != null)
                onWarpySaveClicked(warpyHookedTextArea.getText());
            else
                panel.log("[Alignment/Warpy] WARNING: no TextArea found — matrix not captured.");
        });
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Attach to a new image — resets all captured state. */
    public void attach(ImageData<BufferedImage> imageData) {
        detach();
        if (imageData == null) return;

        currentImageData    = imageData;
        lastKnownStepCount  = imageData.getHistoryWorkflow().size();
        imageHash           = null;
        attachTime          = System.currentTimeMillis();

        // Snapshot existing annotations — ADDED events for these UUIDs are always ignored.
        snapshotAnnotationIds.clear();
        imageData.getHierarchy().getAnnotationObjects()
            .forEach(o -> snapshotAnnotationIds.add(o.getID()));

        // Retroactively populate capturedSteps with the full workflow history so that
        // MetaScriptGenerator has the complete picture from the beginning, not just
        // steps added after qTrace opened.
        List<WorkflowStep> existing = imageData.getHistoryWorkflow().getSteps();
        for (int i = 0; i < existing.size(); i++) {
            capturedSteps.add(serializeStep(existing.get(i), i));
        }
        preExistingStepCount = existing.size();

        imageData.getHistoryWorkflow().addWorkflowListener(this);
        imageData.getHierarchy().addListener(hierarchyListener);
        startClassifierWatcher();
        startObjectClassifierWatcher();

        // Retroactively detect classifiers and cell intensity steps from existing workflow.
        for (WorkflowStep step : existing) {
            if (step instanceof ScriptableWorkflowStep s) {
                detectClassifierFromScript(s.getScript());
                detectCellIntensityFromScript(s.getScript());
            }
        }

        startWarpyFileWatcher();
        snapshotAlignment();
        startAlignmentWatcher();

        refreshManualAnnotationCount();
        panel.setRecordingActive(true);
        panel.updateStepCount(capturedSteps.size(), preExistingStepCount, manualAnnotationCount);
        panel.setGenerateReady(!capturedSteps.isEmpty());
        panel.log("Recording started — " + serverName(imageData));

        computeHashAsync(imageData);
    }

    /** Detach and reset. */
    public void detach() {
        stopAlignmentWatcher();
        stopWarpyFileWatcher();
        unhookWarpyPane();
        currentAlignment = null;
        stopClassifierWatcher();
        stopObjectClassifierWatcher();
        knownClassifiers.clear();
        knownObjectClassifiers.clear();
        objClassifierWarnedAt.clear();
        cellIntensityRecords.clear();
        if (currentImageData != null) {
            currentImageData.getHierarchy().removeListener(hierarchyListener);
            currentImageData.getHistoryWorkflow().removeWorkflowListener(this);
        }
        currentImageData      = null;
        imageHash             = null;
        lastKnownStepCount    = 0;
        manualAnnotationCount = 0;
        capturedSteps.clear();
        annotationStepIndex.clear();
        snapshotAnnotationIds.clear();
        deletedFragments.clear();

        panel.setRecordingActive(false);
        panel.setGenerateReady(false);
        panel.updateStepCount(0, 0, 0);
    }

    // ── WorkflowListener ─────────────────────────────────────────────────────

    @Override
    public void workflowUpdated(Workflow workflow) {
        List<WorkflowStep> steps = workflow.getSteps();
        int newSize = steps.size();
        if (newSize <= lastKnownStepCount) return;

        for (int i = lastKnownStepCount; i < newSize; i++) {
            WorkflowStep step = steps.get(i);
            JsonObject json   = serializeStep(step, i);
            capturedSteps.add(json);
            panel.log("Step " + (i + 1) + " captured: " + step.getName());
            if (step instanceof ScriptableWorkflowStep s) {
                detectClassifierFromScript(s.getScript());
                detectCellIntensityFromScript(s.getScript());
            }
        }
        lastKnownStepCount = newSize;

        refreshManualAnnotationCount();

        panel.updateStepCount(capturedSteps.size(), preExistingStepCount, manualAnnotationCount);
        panel.setGenerateReady(!capturedSteps.isEmpty());
    }

    // ── Serialization ────────────────────────────────────────────────────────

    private JsonObject serializeStep(WorkflowStep step, int order) {
        JsonObject json = new JsonObject();
        json.addProperty("order",         order);
        json.addProperty("command",       step.getName());
        json.addProperty("timestamp",     Instant.now().toString());
        json.addProperty("is_scriptable", step instanceof ScriptableWorkflowStep);

        if (step instanceof ScriptableWorkflowStep s) {
            json.addProperty("script_fragment", s.getScript());
        }

        JsonObject params = new JsonObject();
        Map<String, ?> paramMap = step.getParameterMap();
        if (paramMap != null) paramMap.forEach((k, v) -> params.addProperty(k, String.valueOf(v)));
        json.add("parameters", params);

        return json;
    }

    // ── SHA-256 (background thread — image can be several GB) ────────────────

    private void computeHashAsync(ImageData<BufferedImage> imageData) {
        Thread t = new Thread(() -> {
            try {
                var uris = imageData.getServer().getURIs();
                if (uris.isEmpty()) {
                    panel.log("WARNING: image has no file URI — hash skipped.");
                    return;
                }
                File file = new File(uris.iterator().next().getPath());
                if (!file.exists()) {
                    panel.log("WARNING: image file not found on disk — hash skipped.");
                    return;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) != -1) digest.update(buf, 0, n);
                }
                imageHash = HexFormat.of().formatHex(digest.digest());
                panel.log("SHA-256: " + imageHash.substring(0, 16) + "...");
            } catch (Exception e) {
                panel.log("WARNING: hash error — " + e.getMessage());
            }
        }, "qtrace-hash");
        t.setDaemon(true);
        t.start();
    }

    // ── GeoJSON export of manual annotations ─────────────────────────────────

    /**
     * Exports all annotation objects to a .geojson file.
     * Called by MetaScriptGenerator (Phase 3) before generating the replay script.
     *
     * @param outputDir directory where the .geojson file will be written
     * @return filename of the generated file, or null if no annotations
     */
    public String exportManualAnnotations(Path outputDir) throws IOException {
        if (currentImageData == null) return null;

        Collection<PathObject> annotations =
            currentImageData.getHierarchy().getAnnotationObjects();

        if (annotations.isEmpty()) return null;

        String base = serverName(currentImageData).replaceAll("[^a-zA-Z0-9_-]", "_");
        File out = outputDir.resolve(base + "_annotations.geojson").toFile();

        PathIO.exportObjectsAsGeoJSON(out, annotations);

        panel.log("Annotations exported: " + annotations.size()
                + " objects → " + out.getName());
        return out.getName();
    }

    // ── Annotation fragment builder ───────────────────────────────────────────

    /**
     * Builds the self-contained Groovy fragment that recreates {@code obj}
     * with ALL its current properties: geometry, name, class, color,
     * description (metadata), and locked state.
     * Variable suffix = first 8 hex chars of UUID → no collision in flat Groovy scope.
     */
    private String buildAnnotationFragment(UUID uuid, PathObject obj) {
        String varSuffix = uuid.toString().replace("-", "").substring(0, 8);
        String roiName   = (obj.getROI() != null) ? obj.getROI().getRoiName() : "Object";
        String geoJson   = GsonTools.getInstance(false).toJson(obj);
        String b64       = Base64.getEncoder().encodeToString(
                               geoJson.getBytes(StandardCharsets.UTF_8));
        return "// Replay manual annotation: " + roiName + "\n" +
               "def _b_"   + varSuffix + " = java.util.Base64.getDecoder().decode(\"" + b64 + "\")\n" +
               "def _obj_" + varSuffix + " = qupath.lib.io.GsonTools.getInstance(false)" +
                   ".fromJson(new String(_b_" + varSuffix + ", \"UTF-8\"), qupath.lib.objects.PathObject)\n" +
               "if (_obj_" + varSuffix + " != null) addObject(_obj_" + varSuffix + ")";
    }

    // ── captureManualAnnotation ───────────────────────────────────────────────

    private void captureManualAnnotation(PathObject obj) {
        if (currentImageData == null) return;
        try {
            UUID uuid = obj.getID();
            if (snapshotAnnotationIds.contains(uuid)) return;

            // Persist author attribution on the PathObject (protected API, accessed via reflection)
            ActionLogger.storeAnnotationMeta(obj, "qtrace.author",      System.getProperty("user.name", "unknown"));
            ActionLogger.storeAnnotationMeta(obj, "qtrace.captured_at", Instant.now().toString());

            String roiName  = (obj.getROI() != null) ? obj.getROI().getRoiName() : "Object";
            String fragment = buildAnnotationFragment(uuid, obj);

            Integer existingIdx = annotationStepIndex.get(uuid);
            if (existingIdx != null) {
                // Same UUID re-added without prior REMOVED — retire old, inject fresh
                retireStep(existingIdx);
            }
            var step = new DefaultScriptableWorkflowStep("Manual annotation: " + roiName, fragment);
            currentImageData.getHistoryWorkflow().addStep(step);
            // workflowUpdated() fires synchronously → new entry appended to capturedSteps
            annotationStepIndex.put(uuid, capturedSteps.size() - 1);
        } catch (Exception e) {
            panel.log("WARNING: could not capture annotation — " + e.getMessage());
        }
    }

    // ── Property update (name / class / color / description / locked) ─────────

    private void refreshAnnotationCapture(PathObject obj) {
        UUID uuid = obj.getID();
        if (snapshotAnnotationIds.contains(uuid)) return;
        Integer idx = annotationStepIndex.get(uuid);
        if (idx == null) return;
        try {
            String newFrag  = buildAnnotationFragment(uuid, obj);
            JsonObject existing = capturedSteps.get(idx);
            String oldFrag  = existing.has("script_fragment")
                ? existing.get("script_fragment").getAsString() : null;
            if (newFrag.equals(oldFrag)) return; // nothing changed

            retireStep(idx);
            String roiName = (obj.getROI() != null) ? obj.getROI().getRoiName() : "Object";
            var step = new DefaultScriptableWorkflowStep("Manual annotation: " + roiName, newFrag);
            currentImageData.getHistoryWorkflow().addStep(step);
            annotationStepIndex.put(uuid, capturedSteps.size() - 1);
            panel.log("Annotation updated: " + roiName);
        } catch (Exception e) {
            panel.log("WARNING: annotation refresh failed — " + e.getMessage());
        }
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    private void onAnnotationRemoved(PathObject obj) {
        UUID uuid = obj.getID();
        if (snapshotAnnotationIds.contains(uuid)) return;
        Integer idx = annotationStepIndex.remove(uuid);
        if (idx == null) return;
        retireStep(idx);
        String roiName = (obj.getROI() != null) ? obj.getROI().getRoiName() : "Object";
        panel.log("Annotation deleted: " + roiName + " (removed from meta-script)");
        refreshManualAnnotationCount();
        panel.updateStepCount(capturedSteps.size(), preExistingStepCount, manualAnnotationCount);
    }

    // ── Retire helper ─────────────────────────────────────────────────────────

    private void retireStep(int idx) {
        JsonObject j = capturedSteps.get(idx);
        j.addProperty("deleted", true);
        if (j.has("script_fragment") && !j.get("script_fragment").isJsonNull()) {
            deletedFragments.add(j.get("script_fragment").getAsString());
        }
    }

    // ── Manual annotation counter ─────────────────────────────────────────────

    private void refreshManualAnnotationCount() {
        // Only count annotations drawn *after* qTrace attached — those in annotationStepIndex.
        // Pre-existing annotations (snapshotAnnotationIds) are never tracked here.
        manualAnnotationCount = annotationStepIndex.size();
    }

    // ── Getters (consumed by Phase 3) ────────────────────────────────────────

    public List<JsonObject> getCapturedSteps()            { return Collections.unmodifiableList(capturedSteps); }
    public String           getImageHash()                { return imageHash; }
    public ImageData<BufferedImage> getCurrentImageData() { return currentImageData; }
    public int              getManualAnnotationCount()    { return manualAnnotationCount; }
    public boolean          hasSteps()                    { return !capturedSteps.isEmpty(); }
    public QuPathGUI        getQuPath()                   { return qupath; }
    public void             setScriptRunning(boolean b)   { scriptRunning = b; }
    public Set<UUID>        getSessionAnnotationIds()     { return Collections.unmodifiableSet(annotationStepIndex.keySet()); }

    public Set<String> getDeletedFragments() {
        return Collections.unmodifiableSet(deletedFragments);
    }

    /**
     * Re-serializes every tracked annotation with its CURRENT state (name, class,
     * color, description, locked) and updates capturedSteps if anything changed.
     *
     * Called by QTraceController just before MetaScriptGenerator runs.
     * This is a pull-based fallback for QuPath UI actions that don't consistently
     * fire OTHER_STRUCTURE_CHANGE (e.g. setting name, description, color via panel).
     */
    /**
     * Clears all captured history and restarts tracking from the current workflow
     * position. All annotations currently in the hierarchy become "pre-existing"
     * (they will not be re-captured as new). The classifier watcher keeps running.
     */
    public void resetCapture() {
        if (currentImageData == null) return;

        capturedSteps.clear();
        annotationStepIndex.clear();
        deletedFragments.clear();
        cellIntensityRecords.clear();
        preExistingStepCount  = 0;
        manualAnnotationCount = 0;

        // Promote all current annotations to "pre-existing" so they are not re-captured
        snapshotAnnotationIds.clear();
        currentImageData.getHierarchy().getAnnotationObjects()
            .forEach(o -> snapshotAnnotationIds.add(o.getID()));

        // Track only steps added from this point forward
        lastKnownStepCount = currentImageData.getHistoryWorkflow().size();

        panel.updateStepCount(0, 0, 0);
        panel.setGenerateReady(false);
        panel.log("— Reset — history cleared. Tracking from this point forward.");
    }

    public void refreshAllAnnotationCaptures() {
        if (currentImageData == null || annotationStepIndex.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(annotationStepIndex.keySet())) {
            currentImageData.getHierarchy().getAnnotationObjects().stream()
                .filter(a -> a.getID().equals(uuid))
                .findFirst()
                .ifPresent(this::refreshAnnotationCapture);
        }
    }

    public Map<String, ClassifierRecord> getKnownClassifiers() {
        return Collections.unmodifiableMap(knownClassifiers);
    }

    public List<JsonObject> getKnownObjectClassifiers() {
        return Collections.unmodifiableList(knownObjectClassifiers);
    }

    /** Worst-case ClassifierFidelity across all known classifiers. HIGH if none. */
    public ClassifierFidelity computeClassifierFidelity() {
        return knownClassifiers.values().stream()
            .map(ClassifierRecord::fidelity)
            .reduce(ClassifierFidelity.HIGH, ClassifierFidelity::worst);
    }

    // ── Alignment watcher (v0.5.19 / v0.5.20) ────────────────────────────────

    /** Called on attach() and every 500 ms by the watcher — must run on JavaFX thread. */
    private void snapshotAlignment() {
        if (currentImageData == null) return;
        var server = currentImageData.getServer();

        // ── Path A: QuPath built-in AffineTransformImageServer ────────────────
        if (server instanceof AffineTransformImageServer ats) {
            AffineTransform at = ats.getTransform();
            double[] matrix = new double[6];
            at.getMatrix(matrix);
            if (currentAlignment != null
                    && "AffineServer".equals(currentAlignment.captureSource)
                    && Arrays.equals(matrix, currentAlignment.matrix)) return;

            String movingName = server.getMetadata().getName();
            String movingUri  = "";
            String refName    = "(wrapped server name not available)";
            String refUri     = "";
            try {
                var getWrapped = ats.getClass().getSuperclass()
                    .getDeclaredMethod("getWrappedServer");
                getWrapped.setAccessible(true);
                @SuppressWarnings("unchecked")
                var wrapped = (qupath.lib.images.servers.ImageServer<java.awt.image.BufferedImage>)
                    getWrapped.invoke(ats);
                refName  = wrapped.getMetadata().getName();
                refUri   = wrapped.getURIs().isEmpty() ? ""
                    : wrapped.getURIs().iterator().next().toString();
                var uris = currentImageData.getServer().getURIs();
                if (!uris.isEmpty()) movingUri = uris.iterator().next().toString();
            } catch (Exception ignored) {}

            currentAlignment = new AlignmentRecord(
                Instant.now(), System.getProperty("user.name", "unknown"),
                "AffineServer", "AffineTransform2D",
                matrix, "java_affine",
                imageHash != null ? imageHash : "(pending)",
                movingName, movingUri,
                refName, refUri,
                null, null, null);
            panel.log("[Alignment] AffineServer detected — moving: " + movingName);
            panel.log("[Alignment] Matrix: " + Arrays.toString(matrix));
            panel.setGenerateReady(true);
            return;
        }

        // ── If server changed away from AffineServer, clear that record ───────
        if (currentAlignment != null && "AffineServer".equals(currentAlignment.captureSource)) {
            currentAlignment = null;
            panel.log("[Alignment] AffineServer transform removed.");
        }

        // ── Path B: try to hook the Warpy Save/Create/Update buttons ─────────
        if (warpyHookedButtons.isEmpty()) {
            scanForWarpyWindow();
        } else {
            // Verify the Warpy window is still showing; if not, reset the hook
            boolean warpyOpen = Window.getWindows().stream()
                .filter(w -> w instanceof Stage)
                .map(w -> (Stage) w)
                .anyMatch(s -> s.isShowing()
                    && s.getTitle() != null
                    && s.getTitle().toLowerCase().contains("warpy"));
            if (!warpyOpen) {
                unhookWarpyPane();
                panel.log("[Alignment/Warpy] Dialog closed — hook released.");
            }
        }
    }

    private void startAlignmentWatcher() {
        if (alignmentWatcherThread != null && alignmentWatcherThread.isAlive()) return;
        alignmentWatcherThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(500);
                    Platform.runLater(this::snapshotAlignment);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "qtrace-alignment-watcher");
        alignmentWatcherThread.setDaemon(true);
        alignmentWatcherThread.start();
    }

    private void stopAlignmentWatcher() {
        if (alignmentWatcherThread != null) {
            alignmentWatcherThread.interrupt();
            alignmentWatcherThread = null;
        }
    }

    public AlignmentRecord getAlignmentRecord() { return currentAlignment; }

    // ── Warpy button hook (v0.5.21) ───────────────────────────────────────────

    /** Scans open JavaFX windows for the Warpy dialog and hooks its Save/Create/Update buttons. */
    private void scanForWarpyWindow() {
        for (var window : Window.getWindows()) {
            if (!(window instanceof Stage stage)) continue;
            String title = stage.getTitle();
            if (title != null && title.toLowerCase().contains("warpy") && stage.isShowing()) {
                if (stage.getScene() != null) {
                    // Find the TextArea (for reading value at save time)
                    warpyHookedTextArea = findWarpyTextArea(stage.getScene().getRoot());
                    // Hook the Save/Create/Update buttons
                    hookButtonsInScene(stage.getScene().getRoot());
                    if (!warpyHookedButtons.isEmpty()) {
                        panel.log("[Alignment/Warpy] Hooked " + warpyHookedButtons.size()
                            + " button(s) — capture fires on Save/Create/Update.");
                        return;
                    }
                }
            }
        }
    }

    /** Recursively searches a scene node for the first TextArea (any content — validated at capture time). */
    private TextArea findWarpyTextArea(Node node) {
        if (node instanceof TextArea ta) return ta;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TextArea found = findWarpyTextArea(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Recursively finds buttons matching Save / Create / Update / Apply and hooks them. */
    private void hookButtonsInScene(Node node) {
        if (node instanceof Button btn) {
            String t = btn.getText();
            if (t != null) {
                String lower = t.toLowerCase();
                if (lower.contains("save") || lower.contains("create")
                        || lower.contains("update") || lower.contains("apply")
                        || lower.contains("copy")) {
                    btn.addEventHandler(ActionEvent.ACTION, warpyButtonHandler);
                    warpyHookedButtons.add(btn);
                    panel.log("[Alignment/Warpy]   + hooked: '" + t + "'");
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable())
                hookButtonsInScene(child);
        }
    }

    private void unhookWarpyPane() {
        for (Button btn : warpyHookedButtons)
            btn.removeEventHandler(ActionEvent.ACTION, warpyButtonHandler);
        warpyHookedButtons.clear();
        warpyHookedTextArea = null;
    }

    /** Called when the user clicks Save / Create / Update in the Warpy dialog. */
    private void onWarpySaveClicked(String text) {
        double[] matrix = parseWarpyMatrixText(text);
        if (matrix == null) return;
        if (currentAlignment != null
                && "WarpyTextArea".equals(currentAlignment.captureSource)
                && Arrays.equals(matrix, currentAlignment.matrix)) return;

        String movingName = "(unknown)";
        String movingUri  = "";
        if (currentImageData != null) {
            movingName = currentImageData.getServer().getMetadata().getName();
            var uris = currentImageData.getServer().getURIs();
            if (!uris.isEmpty()) movingUri = uris.iterator().next().toString();
        }

        currentAlignment = new AlignmentRecord(
            Instant.now(), System.getProperty("user.name", "unknown"),
            "WarpyTextArea", "WarpyAffine",
            matrix, "imglib2_rowmajor",
            imageHash != null ? imageHash : "(pending)",
            movingName, movingUri,
            "(reference image — see QuPath project)", "",
            null, null, null);
        panel.log("[Alignment/Warpy] Matrix captured — moving: " + movingName);
        panel.setGenerateReady(true);
    }

    /** Parses the 6-value matrix text shown in the Warpy dialog.
     *  Format: "NaN, -0.000, 47.17\n0.000, NaN, -61.15" — NaN at diagonal → 1.0. */
    private static double[] parseWarpyMatrixText(String text) {
        if (text == null || text.isBlank()) return null;
        String[] tokens = text.trim()
            .replaceAll("[\\r\\n]+", ",")
            .split("[,\\s]+");
        List<String> vals = new ArrayList<>();
        for (String t : tokens) { String s = t.trim(); if (!s.isEmpty()) vals.add(s); }
        if (vals.size() != 6) return null;
        double[] m = new double[6];
        for (int i = 0; i < 6; i++) {
            if (vals.get(i).equalsIgnoreCase("NaN")) {
                m[i] = (i == 0 || i == 4) ? 1.0 : 0.0;
            } else {
                try { m[i] = Double.parseDouble(vals.get(i)); }
                catch (NumberFormatException e) { return null; }
            }
        }
        return m;
    }

    /** Returns true if the text looks like a 2D affine matrix: exactly 6 number-or-NaN tokens. */
    private static boolean looksLikeAffineText(String text) {
        if (text == null || text.isBlank()) return false;
        String[] tokens = text.trim()
            .replaceAll("[\\r\\n]+", ",")
            .split("[,\\s]+");
        int count = 0;
        for (String t : tokens) {
            String s = t.trim();
            if (s.isEmpty()) continue;
            if (!s.equalsIgnoreCase("NaN")) {
                try { Double.parseDouble(s); }
                catch (NumberFormatException e) { return false; }
            }
            count++;
        }
        return count == 6;
    }

    // ── Warpy transform file watcher ─────────────────────────────────────────

    private void startWarpyFileWatcher() {
        var project = qupath.getProject();
        if (project == null || project.getPath() == null) return;
        Path watchDir = project.getPath().getParent();
        if (warpyFileWatcherThread != null && warpyFileWatcherThread.isAlive()) return;

        warpyFileWatcherThread = new Thread(() -> {
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                watchDir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = ws.poll(500, TimeUnit.MILLISECONDS);
                    if (key == null) continue;
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        @SuppressWarnings("unchecked")
                        Path changed = watchDir.resolve(((WatchEvent<Path>) ev).context());
                        String fname = changed.getFileName().toString();
                        if (fname.matches("transform_\\d+_\\d+\\.json")) {
                            Thread.sleep(300);
                            Platform.runLater(() -> onWarpyTransformFile(changed));
                        }
                    }
                    if (!key.reset()) break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Platform.runLater(() -> panel.log("[Warpy file watcher error] " + e.getMessage()));
            }
        }, "qtrace-warpy-file-watcher");
        warpyFileWatcherThread.setDaemon(true);
        warpyFileWatcherThread.start();
    }

    private void stopWarpyFileWatcher() {
        if (warpyFileWatcherThread != null) {
            warpyFileWatcherThread.interrupt();
            warpyFileWatcherThread = null;
        }
    }

    /** Invoked on FX thread when a transform_TARGET_SOURCE.json file is written by Warpy. */
    private void onWarpyTransformFile(Path transformFile) {
        try {
            String fname = transformFile.getFileName().toString();
            java.util.regex.Matcher mat = java.util.regex.Pattern
                .compile("transform_(\\d+)_(\\d+)\\.json").matcher(fname);
            if (!mat.matches()) return;
            String targetId = mat.group(1);
            String sourceId = mat.group(2);

            String json = Files.readString(transformFile);
            double[] matrix = extractWarpyMatrixFromJson(json);
            if (matrix == null) {
                panel.log("[Warpy] Could not parse matrix from " + fname);
                return;
            }

            if (currentAlignment != null
                    && "WarpyFile".equals(currentAlignment.captureSource)
                    && Arrays.equals(matrix, currentAlignment.matrix)) return;

            // Resolve image names by looking up project entries
            String sourceName = "Image-" + sourceId;
            String sourceUri  = "";
            String targetName = "Image-" + targetId;
            String targetUri  = "";
            try {
                var proj = qupath.getProject();
                if (proj != null) {
                    var images = proj.getImageList();
                    for (int i = 0; i < images.size(); i++) {
                        var entry = images.get(i);
                        // Warpy "Image ID" is typically the list index (0-based)
                        if (String.valueOf(i).equals(sourceId)
                                || String.valueOf(entry.getID()).equals(sourceId)) {
                            sourceName = entry.getImageName();
                        }
                        if (String.valueOf(i).equals(targetId)
                                || String.valueOf(entry.getID()).equals(targetId)) {
                            targetName = entry.getImageName();
                        }
                    }
                }
            } catch (Exception ignored) {}

            currentAlignment = new AlignmentRecord(
                Instant.now(), System.getProperty("user.name", "unknown"),
                "WarpyFile", "WarpyAffine",
                matrix, "imglib2_rowmajor",
                imageHash != null ? imageHash : "(pending)",
                sourceName, sourceUri,
                targetName, targetUri,
                fname, sourceId, targetId);
            panel.log("[Alignment/Warpy] File: " + fname);
            panel.log("  Source: " + sourceName + "  →  Target: " + targetName);
            panel.setGenerateReady(true);
        } catch (Exception e) {
            panel.log("[Warpy file parse error] " + e.getMessage());
        }
    }

    /** Extracts a 6-element flat or 2×3 nested numeric array from a Warpy transform JSON. */
    private static double[] extractWarpyMatrixFromJson(String json) {
        try { return findSixElementMatrix(JsonParser.parseString(json)); }
        catch (Exception e) { return null; }
    }

    private static double[] findSixElementMatrix(JsonElement el) {
        if (el.isJsonArray()) {
            var arr = el.getAsJsonArray();
            // Flat 6-element array
            if (arr.size() == 6) {
                double[] m = new double[6];
                for (int i = 0; i < 6; i++) {
                    if (!arr.get(i).isJsonPrimitive()) return null;
                    double v = arr.get(i).getAsDouble();
                    m[i] = Double.isNaN(v) ? ((i == 0 || i == 4) ? 1.0 : 0.0) : v;
                }
                return m;
            }
            // Nested 2×3 array
            if (arr.size() == 2 && arr.get(0).isJsonArray() && arr.get(1).isJsonArray()) {
                var r0 = arr.get(0).getAsJsonArray();
                var r1 = arr.get(1).getAsJsonArray();
                if (r0.size() == 3 && r1.size() == 3) {
                    double[] m = new double[6];
                    for (int c = 0; c < 3; c++) {
                        double v0 = r0.get(c).getAsDouble();
                        double v1 = r1.get(c).getAsDouble();
                        m[c]     = Double.isNaN(v0) ? ((c == 0) ? 1.0 : 0.0) : v0;
                        m[c + 3] = Double.isNaN(v1) ? ((c == 1) ? 1.0 : 0.0) : v1;
                    }
                    return m;
                }
            }
            // Recurse into array elements
            for (var elem : arr) {
                double[] found = findSixElementMatrix(elem);
                if (found != null) return found;
            }
        } else if (el.isJsonObject()) {
            for (var entry : el.getAsJsonObject().entrySet()) {
                double[] found = findSixElementMatrix(entry.getValue());
                if (found != null) return found;
            }
        }
        return null;
    }

    public Map<String, CellIntensityRecord> getCellIntensityRecords() {
        return Collections.unmodifiableMap(cellIntensityRecords);
    }

    // ── Cell intensity classification detection ──────────────────────────────

    /**
     * Parses a workflow step script for a {@code setCellIntensityClassifications(...)} call
     * and records the measurement name + threshold values (1–3).
     * Last Apply per measurement name wins (map is keyed by measurement).
     */
    private void detectCellIntensityFromScript(String script) {
        if (script == null || script.isBlank()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("setCellIntensityClassifications\\s*\\(\\s*\"([^\"]+)\"\\s*,([^)]+)\\)")
            .matcher(script);
        if (!m.find()) return;
        String measurement = m.group(1);
        String[] parts = m.group(2).split(",");
        List<Double> values = new ArrayList<>();
        for (String part : parts) {
            try { values.add(Double.parseDouble(part.trim())); }
            catch (NumberFormatException ignored) {}
        }
        if (values.isEmpty()) return;
        double[] thresholds = new double[values.size()];
        for (int i = 0; i < values.size(); i++) thresholds[i] = values.get(i);
        cellIntensityRecords.put(measurement, new CellIntensityRecord(
            measurement, thresholds, Instant.now(),
            System.getProperty("user.name", "unknown")));
        panel.log("[Cell intensity] '" + measurement + "' → " + Arrays.toString(thresholds));
    }

    // ── Classifier detection from workflow steps (PC-Load) ──────────────────

    /**
     * Scans a Groovy script fragment for string literals that match a classifier
     * JSON file in the project's pixel_classifiers directory.
     * Covers the "Load pixel classifier" dialog workflow (no file-write event fires).
     */
    private void detectClassifierFromScript(String script) {
        if (script == null || script.isBlank()) return;
        var project = qupath.getProject();
        if (project == null || project.getPath() == null) return;

        Path classifierDir = project.getPath().getParent()
            .resolve("classifiers").resolve("pixel_classifiers");
        if (!Files.exists(classifierDir)) return;

        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"([^\"]{1,80})\"")
            .matcher(script);
        while (m.find()) {
            String candidate = m.group(1);
            // Skip obvious non-names (paths, spaces in odd places, etc.)
            if (candidate.contains("/") || candidate.contains("\\")) continue;
            Path classifierFile = classifierDir.resolve(candidate + ".json");
            if (Files.exists(classifierFile) && !knownClassifiers.containsKey(candidate)) {
                panel.log("[PC load] '" + candidate + "' detected in workflow step — loading.");
                loadClassifierFromDisk(classifierFile, candidate);
            }
        }
    }

    /**
     * Registers an already-existing classifier file as a ClassifierRecord.
     * Used when the classifier was loaded (not trained) in the current session.
     * Skips training GeoJSON export (no training data available here).
     */
    private void loadClassifierFromDisk(Path classifierFile, String name) {
        try {
            byte[] rawBytes = Files.readAllBytes(classifierFile);
            String json     = new String(rawBytes, StandardCharsets.UTF_8);
            String sha256   = computeSha256Bytes(rawBytes);

            ClassifierRecord existing = knownClassifiers.get(name);
            if (existing != null && sha256.equals(existing.sha256)) return;

            ClassifierMeta meta  = parseClassifierMeta(json);
            String user          = System.getProperty("user.name", "unknown");
            String imgAtSave     = (imageHash != null) ? imageHash : "(pending)";

            ClassifierRecord record = new ClassifierRecord(
                name, json, sha256, Instant.now(),
                user, imgAtSave,
                meta.type(), meta.outputType(), meta.resolutionUm(),
                meta.features(), meta.channels(), meta.scales(),
                meta.classes(), meta.localNormType(), meta.localNormScale(),
                meta.advancedParams(),
                List.of(), ""
            );
            knownClassifiers.put(name, record);

            panel.log("[Pixel classifier loaded] " + name);
            panel.log("  type   : " + meta.type() + " / " + meta.outputType());
            panel.log("  sha256 : " + sha256.substring(0, 16) + "...");

            // Git commit + TPC JSON for provenance
            try {
                Path dest = QTraceConfig.get().getClassifierDir()
                    .resolve("classifiers").resolve(name + ".json");
                Files.createDirectories(dest.getParent());
                Files.writeString(dest, json);
                record.gitHash = new GitBridge(QTraceConfig.get().getClassifierDir())
                    .commit(dest, "QTrace classifier loaded: " + name
                        + " (user=" + user + ", sha=" + sha256.substring(0, 8) + ")");
                panel.log("  git    : " + record.gitHash);
            } catch (Exception e) {
                panel.log("  WARNING: classifier Git commit failed — " + e.getMessage());
            }

            writeTpcJson(record);
            panel.setGenerateReady(true);

        } catch (Exception e) {
            panel.log("Classifier load error: " + e.getMessage());
        }
    }

    // ── Pixel classifier watcher (PC-2 / PC-3) ──────────────────────────────

    private void startClassifierWatcher() {
        if (watcherThread != null && watcherThread.isAlive()) return;
        var project = qupath.getProject();
        if (project == null || project.getPath() == null) {
            panel.log("[PC watcher] no project open — classifier tracking disabled.");
            return;
        }

        Path watchDir = project.getPath().getParent()
            .resolve("classifiers").resolve("pixel_classifiers");
        try {
            Files.createDirectories(watchDir);
        } catch (IOException e) {
            panel.log("[PC watcher] cannot create classifier dir: " + e.getMessage());
            return;
        }

        watcherThread = new Thread(() -> {
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                watchDir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = ws.poll(500, TimeUnit.MILLISECONDS);
                    if (key == null) continue;
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        @SuppressWarnings("unchecked")
                        Path changed = watchDir.resolve(((WatchEvent<Path>) ev).context());
                        if (changed.toString().endsWith(".json")) {
                            Thread.sleep(300); // let the OS finish writing
                            Platform.runLater(() -> onClassifierSaved(changed));
                        }
                    }
                    if (!key.reset()) break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Platform.runLater(() -> panel.log("[PC watcher error] " + e.getMessage()));
            }
        }, "qtrace-classifier-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        panel.log("[PC watcher] watching " + watchDir.getFileName());
    }

    private void stopClassifierWatcher() {
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
    }

    // ── Object classifier name watcher ───────────────────────────────────────

    private void startObjectClassifierWatcher() {
        if (objClassifierWatcherThread != null && objClassifierWatcherThread.isAlive()) return;
        var project = qupath.getProject();
        if (project == null) return;
        Path watchDir = project.getPath().getParent()
            .resolve("classifiers").resolve("object_classifiers");
        try {
            Files.createDirectories(watchDir);
        } catch (Exception e) {
            panel.log("[OC watcher] cannot create dir: " + e.getMessage());
            return;
        }
        objClassifierWatcherThread = new Thread(() -> {
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                watchDir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = ws.poll(500, TimeUnit.MILLISECONDS);
                    if (key == null) continue;
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                        @SuppressWarnings("unchecked")
                        Path changed = watchDir.resolve(((WatchEvent<Path>) ev).context());
                        if (changed.toString().endsWith(".json")) {
                            Thread.sleep(300);
                            Platform.runLater(() -> onObjectClassifierSaved(changed));
                        }
                    }
                    if (!key.reset()) break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Platform.runLater(() -> panel.log("[OC watcher error] " + e.getMessage()));
            }
        }, "qtrace-object-classifier-watcher");
        objClassifierWatcherThread.setDaemon(true);
        objClassifierWatcherThread.start();
    }

    private void stopObjectClassifierWatcher() {
        if (objClassifierWatcherThread != null) {
            objClassifierWatcherThread.interrupt();
            objClassifierWatcherThread = null;
        }
    }

    private void onObjectClassifierSaved(Path classifierFile) {
        String name = classifierFile.getFileName().toString().replaceAll("\\.json$", "");
        long now = System.currentTimeMillis();
        Long lastSeen = objClassifierWarnedAt.get(name);
        if (lastSeen != null && now - lastSeen < 3000) return;
        objClassifierWarnedAt.put(name, now);

        boolean nameValid = CLASSIFIER_NAME_PATTERN.matcher(name).matches();
        String user = System.getProperty("user.name", "unknown");

        // Always record
        JsonObject rec = new JsonObject();
        rec.addProperty("name",              name);
        rec.addProperty("saved_at",          Instant.now().toString());
        rec.addProperty("saved_by_user",     user);
        rec.addProperty("name_policy_valid", nameValid);
        knownObjectClassifiers.removeIf(o -> name.equals(o.get("name").getAsString()));
        knownObjectClassifiers.add(rec);
        panel.log("[Object classifier saved] " + name
            + (nameValid ? "" : "  ⚠ name does not match naming policy"));

        if (!nameValid) {
            panel.log("  Expected : YYYYMMDD-SGI-ClassType-[...]");
            panel.log("  Example  : 20260525-SGI-Neuron-v1");
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.initOwner(qupath.getStage());
            alert.setTitle("qTrace — Classifier Name Warning");
            alert.setHeaderText("Invalid classifier name format");
            alert.setContentText(
                "The classifier \"" + name + "\" does not match the required naming convention.\n\n"
                + "Expected format:  YYYYMMDD-SGI-ClassType-[...]\n"
                + "Example:          20260525-SGI-Neuron-v1\n\n"
                + "Please rename the classifier and save again.");
            alert.showAndWait();
        }
    }

    // ── onClassifierSaved : attribution + snapshot (PC-3) ───────────────────

    private void onClassifierSaved(Path classifierFile) {
        if (currentImageData == null) return;
        if (System.currentTimeMillis() - attachTime < 2000) return; // anti-rebond

        try {
            String name    = classifierFile.getFileName().toString().replaceAll("\\.json$", "");
            byte[] rawBytes = Files.readAllBytes(classifierFile);
            String json    = new String(rawBytes, StandardCharsets.UTF_8);
            String sha256  = computeSha256Bytes(rawBytes);

            // Skip if this exact version is already recorded
            ClassifierRecord existing = knownClassifiers.get(name);
            if (existing != null && sha256.equals(existing.sha256)) return;

            // Parse metadata
            ClassifierMeta meta = parseClassifierMeta(json);

            // Attribution
            String user      = System.getProperty("user.name", "unknown");
            String imgAtSave = (imageHash != null) ? imageHash : "(pending)";

            // Training annotation snapshot — all annotations present at save time.
            // We don't filter on PathClass here: QuPath may assign the class internally
            // to the classifier dialog without updating the hierarchy PathClass field.
            List<PathObject> training = new ArrayList<>(
                currentImageData.getHierarchy().getAnnotationObjects());
            List<UUID>   trainingIds  = training.stream().map(PathObject::getID).collect(Collectors.toList());
            String       trainHash    = hashAnnotationSet(training);

            ClassifierRecord record = new ClassifierRecord(
                name, json, sha256, Instant.now(),
                user, imgAtSave,
                meta.type(), meta.outputType(), meta.resolutionUm(),
                meta.features(), meta.channels(), meta.scales(),
                meta.classes(), meta.localNormType(), meta.localNormScale(),
                meta.advancedParams(),
                trainingIds, trainHash
            );
            knownClassifiers.put(name, record);

            // ── Log ─────────────────────────────────────────────────────────
            panel.log("[Pixel classifier saved] " + name);
            panel.log("  user      : " + user);
            panel.log("  image SHA : " + (imageHash != null ? imageHash.substring(0,16) + "..." : "(pending)"));
            panel.log("  type      : " + meta.type() + " / " + meta.outputType());
            panel.log("  training  : " + training.size() + " region(s)");
            panel.log("  sha256    : " + sha256.substring(0, 16) + "...");

            // ── Export training GeoJSON (PC-4) ───────────────────────────────
            Path trainingDir    = QTraceConfig.get().getTrainingDir();
            Path classifierDir  = QTraceConfig.get().getClassifierDir();
            try {
                if (!training.isEmpty()) {
                    String geoFname = exportTrainingAnnotations(name, training, trainingDir);
                    record.trainingGeojsonFile = geoFname;
                    panel.log("  training GeoJSON: " + geoFname);
                }
            } catch (Exception e) {
                panel.log("  WARNING: training GeoJSON export failed — " + e.getMessage());
            }

            // ── Git commit classifier file (PC-5) ────────────────────────────
            try {
                Path dest = classifierDir.resolve("classifiers").resolve(name + ".json");
                Files.createDirectories(dest.getParent());
                Files.writeString(dest, json);
                record.gitHash = new GitBridge(classifierDir).commit(dest,
                    "QTrace classifier: " + name
                    + " (user=" + user + ", img=" + sha256.substring(0,8) + ")");
                panel.log("  git       : " + record.gitHash);
            } catch (Exception e) {
                panel.log("  WARNING: classifier Git commit failed — " + e.getMessage());
            }

            // ── TPC JSON (PC-TPC) ────────────────────────────────────────────
            writeTpcJson(record);

            // ── Enable Generate button — classifier data alone is worth exporting ─
            panel.setGenerateReady(true);

        } catch (Exception e) {
            panel.log("Classifier capture error: " + e.getMessage());
        }
    }

    // ── TPC JSON writer (PC-TPC) ─────────────────────────────────────────────

    private void writeTpcJson(ClassifierRecord record) {
        try {
            String imageName = serverName(currentImageData);
            String tsShort   = java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd_HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
            String safeName  = imageName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String filename  = "TPC-" + safeName + "-" + tsShort + ".json";

            Path outDir = QTraceConfig.get().getTrainingDir();
            Files.createDirectories(outDir);

            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().disableHtmlEscaping().create();
            JsonObject root = new JsonObject();
            root.addProperty("qtrace_version",            QTraceController.VERSION);
            root.addProperty("generated_at",              Instant.now().toString());
            root.addProperty("classifier_name",           record.name);
            root.addProperty("classifier_type",           record.classifierType);
            root.addProperty("output_type",               record.outputType != null ? record.outputType : "Classification");
            root.addProperty("resolution_um",             record.resolutionUm);
            root.add("features",                          record.features);
            root.addProperty("n_channels",                record.channels.size());
            root.add("channels",                          record.channels);
            root.add("scales",                            record.scales);
            root.addProperty("local_normalization",       record.localNormType != null ? record.localNormType : "");
            root.addProperty("local_normalization_scale", record.localNormScale);
            root.add("advanced_params",                   record.advancedParams);
            root.add("classes",                           record.classes);
            root.addProperty("training_annotation_count", record.trainingAnnotationIds.size());
            root.addProperty("training_geojson",          record.trainingGeojsonFile != null ? record.trainingGeojsonFile : "");
            root.addProperty("training_annotation_hash",  record.trainingAnnotationHash);
            root.addProperty("image_name",                imageName);
            root.addProperty("image_sha256",              record.imageHashAtSave != null ? record.imageHashAtSave : "");
            root.addProperty("classifier_sha256",         record.sha256);
            root.addProperty("saved_by",                  record.savedByUser);
            root.addProperty("saved_at",                  record.savedAt.toString());

            // Embed the trained model as a nested JSON object
            try {
                root.add("classifier_json",
                    JsonParser.parseString(record.jsonContent).getAsJsonObject());
            } catch (Exception ignored) {
                root.addProperty("classifier_json_b64",
                    Base64.getEncoder().encodeToString(
                        record.jsonContent.getBytes(StandardCharsets.UTF_8)));
            }

            Files.writeString(outDir.resolve(filename), gson.toJson(root));
            record.tpcFilePath = filename;
            panel.log("  TPC JSON    : " + filename);

        } catch (Exception e) {
            panel.log("  WARNING: TPC JSON write failed — " + e.getMessage());
        }
    }

    // ── Integrity check (PC-Integrity) ───────────────────────────────────────

    private void checkTrainingIntegrity(Collection<PathObject> changedObjects) {
        Set<UUID> changedIds = changedObjects.stream()
            .map(PathObject::getID)
            .collect(Collectors.toSet());

        for (ClassifierRecord record : knownClassifiers.values()) {
            if (record.modifiedAfterTraining) continue;
            boolean affected = record.trainingAnnotationIds.stream().anyMatch(changedIds::contains);
            if (affected) {
                record.modifiedAfterTraining = true;
                panel.log("⚠ INTEGRITY: training data for '" + record.name
                    + "' modified after save → Classifier_Fidelity = DEGRADED");
            }
        }
    }

    // ── Training annotation export (PC-4) ────────────────────────────────────

    private String exportTrainingAnnotations(String classifierName,
                                              Collection<PathObject> annotations,
                                              Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String base  = serverName(currentImageData).replaceAll("[^a-zA-Z0-9_-]", "_");
        String fname = base + "_training_" + classifierName + ".geojson";
        PathIO.exportObjectsAsGeoJSON(outputDir.resolve(fname).toFile(), annotations);
        return fname;
    }

    // ── Classifier JSON parser ───────────────────────────────────────────────

    private record ClassifierMeta(String type, String outputType, double resolutionUm,
                                   JsonArray features, JsonArray channels, JsonArray scales,
                                   JsonArray classes, String localNormType, double localNormScale,
                                   JsonObject advancedParams) {}

    /** Mutable collector threaded through {@link #collectFromOpTree}. */
    private static class OpTreeCollector {
        final JsonArray features = new JsonArray();
        final JsonArray channels = new JsonArray();
        final JsonArray scales   = new JsonArray();
        String  modelType      = null;
        String  localNormType  = null;
        double  localNormScale = 0.0;
        boolean channelsDone   = false;
        boolean scalesDone     = false;
        boolean normDone       = false;
    }

    private static ClassifierMeta parseClassifierMeta(String json) {
        String          type       = "Unknown";
        String          outputType = "Classification";
        double          resUm      = 0.0;
        JsonArray       classes    = new JsonArray();
        OpTreeCollector c          = new OpTreeCollector();
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();

            // ── Classifier type ────────────────────────────────────────────────
            // QuPath 0.7.x: "pixel_classifier_type"; older: "classifierType"
            if (root.has("pixel_classifier_type"))
                type = root.get("pixel_classifier_type").getAsString();
            else if (root.has("classifierType"))
                type = root.get("classifierType").getAsString();

            // ── Output type ────────────────────────────────────────────────────
            if (root.has("outputType"))
                outputType = root.get("outputType").getAsString();

            // ── Metadata block ─────────────────────────────────────────────────
            if (root.has("metadata")) {
                var meta = root.getAsJsonObject("metadata");
                if (meta.has("outputType"))
                    outputType = meta.get("outputType").getAsString();

                // Classes: QuPath 0.7.x → "outputChannels" [{name, color}]
                if (meta.has("outputChannels")) {
                    for (var el : meta.getAsJsonArray("outputChannels")) {
                        if (el.isJsonObject() && el.getAsJsonObject().has("name"))
                            classes.add(el.getAsJsonObject().get("name").getAsString());
                    }
                } else if (meta.has("classNames")) {
                    meta.getAsJsonArray("classNames").forEach(classes::add);
                }

                // Resolution: QuPath 0.7.x → {pixelWidth: {value, unit}}
                if (meta.has("inputResolution")) {
                    var res = meta.getAsJsonObject("inputResolution");
                    if (res.has("pixelWidth")) {
                        var pw = res.get("pixelWidth");
                        if (pw.isJsonObject() && pw.getAsJsonObject().has("value"))
                            resUm = pw.getAsJsonObject().get("value").getAsDouble();
                        else if (pw.isJsonPrimitive())
                            resUm = pw.getAsDouble();
                    } else if (res.has("pixelWidthMicrons")) {
                        resUm = res.get("pixelWidthMicrons").getAsDouble();
                    }
                }
            }

            // ── Op tree: features, channels, scales, norm, model type ─────────
            if (root.has("op"))
                collectFromOpTree(root.getAsJsonObject("op"), c);
            if (c.modelType != null) type = c.modelType;

            // ── Fallback: older "feature_calculator" format ────────────────────
            if (c.features.isEmpty() && root.has("feature_calculator")) {
                var fc = root.getAsJsonObject("feature_calculator");
                if (fc.has("features")) fc.getAsJsonArray("features").forEach(c.features::add);
                if (fc.has("channels")) fc.getAsJsonArray("channels").forEach(c.channels::add);
                if (fc.has("scales"))   fc.getAsJsonArray("scales").forEach(c.scales::add);
            }

        } catch (Exception ignored) {}
        JsonObject adv = new JsonObject();
        try { adv = extractAdvancedParams(JsonParser.parseString(json).getAsJsonObject()); }
        catch (Exception ignored) {}
        return new ClassifierMeta(type, outputType, resUm,
                                   c.features, c.channels, c.scales,
                                   classes, c.localNormType, c.localNormScale, adv);
    }

    /**
     * Extracts Advanced Options parameters by searching the full classifier JSON tree.
     * Uses a tolerant recursive lookup so the code is robust to QuPath version changes —
     * if a key moves between versions, it is still found wherever it ends up.
     *
     * Canonical output keys (snake_case) → possible JSON key names tried in order:
     *   num_threads          → nThreads, numThreads, numberOfThreads
     *   max_samples          → maxSamples, maximumSamples, maxTrainingSamples
     *   rng_seed             → seed, rngSeed, randomSeed
     *   reweight_samples     → reweightSamples, reweightTrainingSamples
     *   feature_normalization→ normalization, featureNormalization
     *   feature_reduction    → featureReduction, reduction, dimensionReduction
     *   pca_retained_variance→ pcaRetainedVariance, pcaVariance, pcaComponents
     *   boundary_strategy    → boundaryStrategy, annotationBoundaryStrategy
     *   boundary_thickness   → boundaryThickness, annotationBoundaryThickness
     */
    private static JsonObject extractAdvancedParams(JsonObject root) {
        JsonObject out = new JsonObject();

        Object[][] params = {
            {"num_threads",           new String[]{"nThreads",        "numThreads",           "numberOfThreads"}},
            {"max_samples",           new String[]{"maxSamples",       "maximumSamples",        "maxTrainingSamples"}},
            {"rng_seed",              new String[]{"seed",             "rngSeed",               "randomSeed"}},
            {"reweight_samples",      new String[]{"reweightSamples",  "reweightTrainingSamples"}},
            {"feature_normalization", new String[]{"featureNormalization", "normalization"}},
            {"feature_reduction",     new String[]{"featureReduction", "reduction",             "dimensionReduction"}},
            {"pca_retained_variance", new String[]{"pcaRetainedVariance","pcaVariance",         "pcaComponents"}},
            {"boundary_strategy",     new String[]{"boundaryStrategy", "annotationBoundaryStrategy"}},
            {"boundary_thickness",    new String[]{"boundaryThickness","annotationBoundaryThickness"}},
            // Model architecture — serialized in classifier JSON under statmodel.opencv_ml_ann_mlp
            {"train_method",          new String[]{"train_method"}},
            {"train_iterations",      new String[]{"iterations"}},
            {"train_epsilon",         new String[]{"epsilon"}},
            {"activation_function",   new String[]{"activation_function"}},
            {"layer_sizes",           new String[]{"layer_sizes"}},
            {"input_features_n",      new String[]{"inputLength"}},
        };

        for (Object[] param : params) {
            String outKey = (String) param[0];
            for (String key : (String[]) param[1]) {
                JsonElement found = findInTree(root, key);
                if (found != null) { out.add(outKey, found); break; }
            }
        }
        return out;
    }

    /** Depth-first search for {@code key} anywhere in the JSON tree (objects and arrays). */
    private static JsonElement findInTree(JsonObject node, String key) {
        if (node.has(key)) return node.get(key);
        for (var entry : node.entrySet()) {
            JsonElement val = entry.getValue();
            if (val.isJsonObject()) {
                JsonElement found = findInTree(val.getAsJsonObject(), key);
                if (found != null) return found;
            } else if (val.isJsonArray()) {
                for (var el : val.getAsJsonArray()) {
                    if (el.isJsonObject()) {
                        JsonElement found = findInTree(el.getAsJsonObject(), key);
                        if (found != null) return found;
                    }
                }
            }
        }
        return null;
    }

    /** Recursively walks the QuPath classifier "op" tree; populates the collector. */
    private static void collectFromOpTree(JsonObject node, OpTreeCollector c) {
        // Feature type names (e.g. ["Mean", "Std dev"])
        if (node.has("features") && node.get("features").isJsonArray())
            node.getAsJsonArray("features").forEach(c.features::add);

        // Input channels — QuPath 0.7.x: colorTransforms[].channelName at data.op.channels nodes
        if (!c.channelsDone && node.has("colorTransforms") && node.get("colorTransforms").isJsonArray()) {
            for (var el : node.getAsJsonArray("colorTransforms")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("channelName"))
                    c.channels.add(el.getAsJsonObject().get("channelName").getAsString());
            }
            if (!c.channels.isEmpty()) c.channelsDone = true;
        }
        // Input channels fallback — older QuPath format with channels array
        if (!c.channelsDone && node.has("channels") && node.get("channels").isJsonArray()) {
            node.getAsJsonArray("channels").forEach(c.channels::add);
            if (!c.channels.isEmpty()) c.channelsDone = true;
        }

        // Gaussian scales — QuPath 0.7.x: sigmaX on op.filters.multiscale nodes (collect all unique values)
        if (node.has("sigmaX") && node.get("sigmaX").isJsonPrimitive()) {
            double sigma = node.get("sigmaX").getAsDouble();
            boolean dup = false;
            for (var el : c.scales)
                if (el.isJsonPrimitive() && Math.abs(el.getAsDouble() - sigma) < 1e-9) { dup = true; break; }
            if (!dup) c.scales.add(sigma);
        }

        // Model class name (e.g. "ANN_MLP") — first found wins
        if (c.modelType == null && node.has("model") && node.get("model").isJsonObject()) {
            var model = node.getAsJsonObject("model");
            if (model.has("class")) c.modelType = model.get("class").getAsString();
        }

        // Local normalization — detect by "type" or dedicated fields
        if (!c.normDone && node.has("type")) {
            String nodeType = node.get("type").getAsString().toLowerCase();
            if (nodeType.contains("normaliz")) {
                c.localNormType = node.get("type").getAsString();
                // sub-field may refine the type name
                for (String k : new String[]{"normalizeType", "normType", "normalization"})
                    if (node.has(k) && node.get(k).isJsonPrimitive())
                        c.localNormType = node.get(k).getAsString();
                // radius / scale
                for (String k : new String[]{"scale", "radius", "localNormalizationRadius"})
                    if (node.has(k) && node.get(k).isJsonPrimitive())
                        { c.localNormScale = node.get(k).getAsDouble(); break; }
                c.normDone = true;
            }
        }

        // Recurse into single "op" child
        if (node.has("op") && node.get("op").isJsonObject())
            collectFromOpTree(node.getAsJsonObject("op"), c);

        // Recurse into "ops" array
        if (node.has("ops") && node.get("ops").isJsonArray())
            for (var el : node.getAsJsonArray("ops"))
                if (el.isJsonObject())
                    collectFromOpTree(el.getAsJsonObject(), c);
    }

    // ── Training annotation hash ─────────────────────────────────────────────

    private String hashAnnotationSet(Collection<PathObject> annotations) {
        try {
            String combined = annotations.stream()
                .filter(o -> o.getPathClass() != null)
                .sorted(Comparator.comparing(o -> o.getID().toString()))
                .map(o -> o.getID() + ":" + o.getPathClass().getName())
                .collect(Collectors.joining(","));
            return computeSha256Bytes(combined.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    // ── Generic SHA-256 ──────────────────────────────────────────────────────

    private static String computeSha256Bytes(byte[] bytes) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update(bytes);
        return HexFormat.of().formatHex(d.digest());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String serverName(ImageData<BufferedImage> d) {
        return d.getServer().getMetadata().getName();
    }

    // PathObject.storeMetadataValue / retrieveMetadataValue are protected — access via reflection
    static void storeAnnotationMeta(PathObject obj, String key, String value) {
        try {
            var m = PathObject.class.getDeclaredMethod("storeMetadataValue", String.class, String.class);
            m.setAccessible(true);
            m.invoke(obj, key, value);
        } catch (Exception ignored) {}
    }

    static String retrieveAnnotationMeta(PathObject obj, String key) {
        try {
            var m = PathObject.class.getDeclaredMethod("retrieveMetadataValue", String.class);
            m.setAccessible(true);
            Object val = m.invoke(obj, key);
            return val != null ? val.toString() : null;
        } catch (Exception ignored) { return null; }
    }
}
