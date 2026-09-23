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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.ProvenanceDiff.Finding;
import io.qtrace.ProvenanceDiff.Kind;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * "Why?" window: explains where an image's current state diverges from its latest stamp —
 * edited .qtrace fields, modified satellite files, and annotation/detection changes in the
 * .qpdata. Reference = the .qtcert's qtrace_payload when found, else the stamped session.
 */
public final class ProvenanceDiffDialog {

    private static final String BG       = "#1e1e2e";
    private static final String SURFACE  = "#181825";
    private static final String TEXT     = "#cdd6f4";
    private static final String MUTED    = "#6c7086";
    private static final String GREEN    = "#a6e3a1";
    private static final String PEACH    = "#fab387";
    private static final String RED      = "#f38ba8";

    private ProvenanceDiffDialog() {}

    private record Section(String title, String summary, String summaryColor, List<Finding> findings) {}

    public static void show(Window owner, JsonObject root, File qtraceFile, ProjectImageEntry<?> entry) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("qTrace — Why does this image differ from its stamp?");
        VBox content = new VBox(10);
        content.setPadding(new Insets(14));
        content.setStyle("-fx-background-color:" + BG + ";");
        content.getChildren().add(label("Analysing…", MUTED, 12, FontWeight.NORMAL));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background:" + BG + ";-fx-background-color:" + BG + ";");
        stage.setScene(new Scene(scroll, 720, 520));
        stage.show();

        Thread t = new Thread(() -> {
            List<Section> sections;
            String reference;
            try {
                JsonObject session = latestValidatedSession(root);
                JsonObject stamped = findCertPayload(qtraceFile, session);
                reference = stamped != null
                    ? "Reference: the signed certificate (.qtcert) of this stamp"
                    : "Reference: the stamped session in the .qtrace (no certificate found)";
                sections = analyse(root, session, stamped, qtraceFile, entry);
            } catch (Exception e) {
                reference = "Analysis failed: " + e.getMessage();
                sections = List.of();
            }
            String ref = reference;
            List<Section> result = sections;
            Platform.runLater(() -> render(content, ref, result));
        }, "qtrace-provenance-diff");
        t.setDaemon(true);
        t.start();
    }

    // ── Analysis (off FX thread) ─────────────────────────────────────────────

    private static List<Section> analyse(JsonObject root, JsonObject session, JsonObject stamped,
                                         File qtraceFile, ProjectImageEntry<?> entry) throws Exception {
        List<Section> out = new ArrayList<>();
        JsonObject val = session.getAsJsonObject("validation");
        JsonObject ref = stamped != null ? stamped : session;

        // 1 ── .qtrace
        Boolean sigValid = StampIntegrity.verifyValidation(val, root, true);
        String sigText = sigValid == null ? "Stamp not signed — nothing to verify"
            : sigValid ? "Signature valid" : "Signature INVALID — the stamp was edited after signing";
        String sigColor = sigValid == null ? MUTED : sigValid ? GREEN : RED;
        List<Finding> fields = new ArrayList<>();
        if (stamped != null) {
            fields = ProvenanceDiff.diffSession(stamped, session, str(root, "status"));
        } else if (Boolean.FALSE.equals(sigValid)) {
            sigText += " (no certificate to compare with: the edited field cannot be pinpointed)";
        }
        out.add(new Section(".qtrace file", sigText, sigColor, fields));

        // 2 ── satellite files
        Path exportDir = qtraceFile != null ? qtraceFile.toPath().getParent() : null;
        List<Finding> files = new ArrayList<>(ProvenanceDiff.checkFiles(arr(ref, "external_files"), exportDir));
        JsonObject ann = obj(ref, "annotations");
        if (ann != null)
            files.addAll(ProvenanceDiff.checkGeoJson(str(ann, "geojson_file"), str(ann, "geojson_sha256"),
                QTraceConfig.get().outputTrainingDir()));
        out.add(new Section("Satellite files", files.isEmpty() ? "Unchanged since the stamp" : null, GREEN, files));

        // 3 ── .qpdata
        Path qpdata = entry != null && entry.getEntryPath() != null ? entry.getEntryPath().resolve("data.qpdata") : null;
        String stampedSha = str(val, "qpdata_sha256");
        if (qpdata == null || !Files.exists(qpdata)) {
            out.add(new Section("Image data (.qpdata)", "Not found in the open project — cannot compare", MUTED, List.of()));
        } else if (stampedSha == null) {
            out.add(new Section("Image data (.qpdata)", "The stamp recorded no .qpdata fingerprint", MUTED, List.of()));
        } else if (stampedSha.equals(io.qtrace.chain.Hashing.sha256Hex(qpdata))) {
            out.add(new Section("Image data (.qpdata)", "Identical to the stamped file", GREEN, List.of()));
        } else {
            var data = entry.readImageData();
            var hierarchy = data.getHierarchy();
            List<Finding> dataFindings = new ArrayList<>(ProvenanceDiff.diffAnnotations(
                ann != null ? arr(ann, "details") : new JsonArray(),
                annotationDetails(hierarchy.getAnnotationObjects())));
            dataFindings.addAll(ProvenanceDiff.diffDetections(obj(ref, "detections"),
                QTraceExporter.buildDetectionsObject(data)));
            String summary = dataFindings.isEmpty()
                ? "File differs from the stamp, but annotations and detections are unchanged (e.g. measurements, metadata)"
                : "File differs from the stamp";
            out.add(new Section("Image data (.qpdata)", summary, PEACH, dataFindings));
        }
        return out;
    }

    private static JsonArray annotationDetails(Collection<PathObject> annotations) {
        JsonArray arr = new JsonArray();
        for (PathObject a : annotations) {
            ROI r = a.getROI();
            if (r == null) continue;
            JsonObject d = new JsonObject();
            d.addProperty("uuid", a.getID().toString());
            d.addProperty("class", a.getPathClass() != null ? a.getPathClass().getName() : "(unclassified)");
            d.addProperty("roi_type", r.getRoiName());
            d.addProperty("centroid_x", r.getCentroidX());
            d.addProperty("centroid_y", r.getCentroidY());
            d.addProperty("area_px2", r.getArea());
            arr.add(d);
        }
        return arr;
    }

    static JsonObject latestValidatedSession(JsonObject root) {
        JsonArray sessions = arr(root, "sessions");
        for (int i = sessions.size() - 1; i >= 0; i--) {
            JsonObject s = sessions.get(i).getAsJsonObject();
            if (obj(s, "validation") != null) return s;
        }
        throw new IllegalStateException("no stamped session in this .qtrace");
    }

    /** The .qtcert whose qtrace_payload is this session, in <exportDir>/case_<caseId>/certs/. */
    private static JsonObject findCertPayload(File qtraceFile, JsonObject session) {
        String caseId = str(obj(session, "validation"), "case_id");
        String sessionId = str(session, "session_id");
        if (qtraceFile == null || caseId == null || sessionId == null) return null;
        Path certs = qtraceFile.toPath().getParent()
            .resolve("case_" + caseId.replaceAll("[^a-zA-Z0-9._-]", "_")).resolve("certs");
        if (!Files.isDirectory(certs)) return null;
        try (Stream<Path> files = Files.list(certs)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".qtcert"))::iterator) {
                try {
                    JsonObject payload = obj(JsonParser.parseString(Files.readString(p)).getAsJsonObject(), "qtrace_payload");
                    if (payload != null && sessionId.equals(str(payload, "session_id"))) return payload;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private static void render(VBox content, String reference, List<Section> sections) {
        content.getChildren().setAll(label(reference, MUTED, 11, FontWeight.NORMAL));
        for (Section s : sections) {
            VBox box = new VBox(4);
            box.setPadding(new Insets(8, 10, 8, 10));
            box.setStyle("-fx-background-color:" + SURFACE + ";-fx-background-radius:4;");
            box.getChildren().add(label(s.title(), TEXT, 13, FontWeight.BOLD));
            if (s.summary() != null) box.getChildren().add(label(s.summary(), s.summaryColor(), 12, FontWeight.NORMAL));
            for (Finding f : s.findings()) {
                box.getChildren().add(label(icon(f.kind()) + "  " + f.subject(), color(f.kind()), 12, FontWeight.BOLD));
                box.getChildren().add(label("      stamped : " + f.stamped(), MUTED, 11, FontWeight.NORMAL));
                box.getChildren().add(label("      now     : " + f.current(), TEXT, 11, FontWeight.NORMAL));
            }
            content.getChildren().add(box);
        }
    }

    private static String icon(Kind k) {
        return switch (k) {
            case FIELD, FILE, ANNOTATION_MODIFIED, DETECTIONS -> "≠";
            case ANNOTATION_REMOVED -> "−";
            case ANNOTATION_ADDED -> "+";
            case ANNOTATION_RECREATED -> "↻";
            case NOT_COMPARABLE -> "?";
        };
    }

    private static String color(Kind k) {
        return switch (k) {
            case ANNOTATION_RECREATED, NOT_COMPARABLE -> PEACH;
            default -> RED;
        };
    }

    private static Label label(String text, String color, int size, FontWeight weight) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setTextFill(Color.web(color));
        l.setFont(Font.font("System", weight, size));
        return l;
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
}
