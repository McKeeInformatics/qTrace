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

import io.qtrace.player.ext.Compat;
import io.qtrace.player.ext.ExtensionHandlerRegistry;
import io.qtrace.player.ext.InstanSegHandler;
import io.qtrace.player.ext.QTraceExtensionHandler;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-flight checklist shown after a replay script is ready.
 * Verifies project, image, extension, and model requirements.
 */
class QTracePreflightDialog {

    // ── Non-UI compatibility check — reused by the Player, no Stage/Scene involved ─────

    public record ExtCheck(String name, boolean present, Compat compat, String caveat, String installUrl) {}

    public record CompatResult(
        Compat overall,
        boolean projectAlreadyOpen,
        List<ExtCheck> extensions,
        Map<String, QTraceExtensionHandler.ModelStatus> modelStatuses
    ) {}

    /**
     * Runs the same matching logic {@link #show()} renders, without building any JavaFX node.
     * Used by the Player for the compat pass before Play and for "skip non-scriptable steps".
     */
    public static CompatResult check(QuPathGUI qupath, QTraceReplayEngine.PreflightInfo info) {
        String currentProject = getCurrentProjectName(qupath);
        boolean projectAlreadyOpen = !info.projectName().isBlank()
            && !currentProject.isBlank()
            && currentProject.equalsIgnoreCase(info.projectName());

        List<String> loadedExts = getLoadedExtensionNames(qupath);
        List<ExtCheck> checks = new ArrayList<>();
        for (String req : info.requiredExtensions().keySet()) {
            boolean present = loadedExts.stream().anyMatch(n ->
                n.toLowerCase().contains(req.toLowerCase())
                || req.toLowerCase().contains(n.toLowerCase()));
            QTraceExtensionHandler ke = findKnownExt(req);
            Compat compat = ke != null ? effectiveCompat(ke, info) : Compat.PARTIAL;
            String caveat = ke != null ? ke.getCaveat() : "";
            String installUrl = ke != null ? ke.getInstallUrl() : FALLBACK_URL;
            checks.add(new ExtCheck(req, present, compat, caveat, installUrl));
        }

        Map<String, QTraceExtensionHandler.ModelStatus> modelStatuses = new LinkedHashMap<>();
        for (Map.Entry<String, String> m : info.modelPaths().entrySet()) {
            QTraceExtensionHandler.ModelStatus status = ExtensionHandlerRegistry.get()
                .findByName("instanseg")
                .map(h -> h.getModelStatus(m.getKey(), m.getValue()))
                .orElse(QTraceExtensionHandler.ModelStatus.MISSING);
            modelStatuses.put(m.getKey(), status);
        }

        return new CompatResult(computeOverall(checks, modelStatuses), projectAlreadyOpen, checks, modelStatuses);
    }

    private static Compat computeOverall(List<ExtCheck> checks, Map<String, QTraceExtensionHandler.ModelStatus> modelStatuses) {
        boolean modelsOk = modelStatuses.values().stream()
            .noneMatch(s -> s == QTraceExtensionHandler.ModelStatus.MISSING);
        if (checks.isEmpty()) return modelsOk ? Compat.FULL : Compat.PARTIAL;
        boolean anyNotScriptableMissing = checks.stream()
            .anyMatch(c -> !c.present() && c.compat() == Compat.NOT_SCRIPTABLE);
        if (anyNotScriptableMissing) return Compat.NOT_SCRIPTABLE;
        boolean allPresent = checks.stream().allMatch(ExtCheck::present) && modelsOk;
        boolean allFull = checks.stream().allMatch(c -> c.compat() == Compat.FULL);
        return (allPresent && allFull) ? Compat.FULL : Compat.PARTIAL;
    }

    private static final String BG      = "#1e1e2e";
    private static final String BORDER  = "#313244";
    private static final String TEXT    = "#cdd6f4";
    private static final String MUTED   = "#6c7086";
    private static final String BLUE    = "#89b4fa";
    private static final String GREEN   = "#a6e3a1";
    private static final String YELLOW  = "#f9e2af";
    private static final String RED     = "#f38ba8";

    private static final String FALLBACK_URL =
        "https://qupath.readthedocs.io/en/stable/docs/intro/extensions.html";

    // ── Instance ──────────────────────────────────────────────────────────────

    private final QuPathGUI qupath;
    private final QTraceReplayEngine.PreflightInfo info;
    private final Stage parentStage;
    private final Runnable onConfirm;

    QTracePreflightDialog(QuPathGUI qupath, QTraceReplayEngine.PreflightInfo info,
                          Stage parentStage, Runnable onConfirm) {
        this.qupath      = qupath;
        this.info        = info;
        this.parentStage = parentStage;
        this.onConfirm   = onConfirm;
    }

    // ── Show ──────────────────────────────────────────────────────────────────

    void show() {
        CompatResult result = check(qupath, info);
        boolean projectAlreadyOpen = result.projectAlreadyOpen();

        Map<String, Boolean> extStatus = new LinkedHashMap<>();
        for (ExtCheck c : result.extensions()) extStatus.put(c.name(), c.present());
        boolean allExtsOk = extStatus.isEmpty()
            || extStatus.values().stream().allMatch(Boolean::booleanValue);

        Stage dialog = new Stage();
        dialog.initOwner(parentStage);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("qTrace — Before You Run");
        dialog.setResizable(false);

        Label header = styledLabel("Before running the replay script", TEXT, 14, FontWeight.BOLD);
        VBox steps = new VBox(14);

        // Step 1 — Open project
        VBox step1 = new VBox(3);
        step1.getChildren().add(stepRow("1", "Open project",
            info.projectName().isBlank() ? "(unknown)" : "\"" + info.projectName() + "\"", TEXT));
        if (projectAlreadyOpen)
            step1.getChildren().add(indented(styledLabel("✅  Already open", GREEN, 11, FontWeight.NORMAL)));
        steps.getChildren().add(step1);

        // Step 2 — Open image
        steps.getChildren().add(stepRow("2", "Open image",
            info.imageName().isBlank() ? "(unknown)" : "\"" + info.imageName() + "\"", BLUE));

        // Step 3 — Extensions & models
        VBox step3 = new VBox(4);
        step3.getChildren().add(stepRow("3", "Extensions & models", null, null));

        if (extStatus.isEmpty()) {
            step3.getChildren().add(indented(
                styledLabel("No extension data in this .qtrace", MUTED, 11, FontWeight.NORMAL)));
        } else if (allExtsOk) {
            step3.getChildren().add(indented(
                styledLabel("✅  All extensions matched", GREEN, 11, FontWeight.NORMAL)));
            // Still show compat badges for found extensions
            for (String name : extStatus.keySet()) {
                QTraceExtensionHandler ke = findKnownExt(name);
                if (ke != null) {
                    Compat compat = effectiveCompat(ke);
                    String prefix = compat == Compat.FULL ? "✅  " : "    ";
                    step3.getChildren().add(indented(
                        withTooltip(styledLabel(prefix + name + "  " + compat.label,
                            compat.color, 10, FontWeight.NORMAL), tooltipFor(ke, compat))));
                }
            }
        } else {
            for (Map.Entry<String, Boolean> e : extStatus.entrySet()) {
                String name = e.getKey();
                String ver  = info.requiredExtensions().get(name);
                boolean ok  = e.getValue();
                String suffix = ver != null && !ver.isBlank() ? " v" + ver : "";
                QTraceExtensionHandler ke = findKnownExt(name);

                if (ok) {
                    Compat compat = ke != null ? effectiveCompat(ke) : null;
                    String compatTag = compat != null ? "  " + compat.label : "";
                    step3.getChildren().add(indented(
                        withTooltip(styledLabel("✅  " + name + suffix + compatTag,
                            compat != null ? compat.color : GREEN, 11, FontWeight.NORMAL),
                            tooltipFor(ke, compat))));
                } else {
                    step3.getChildren().add(indentedRow(missingExtRow(name, suffix, ke)));
                }
            }
        }

        // Models sub-section
        if (!info.modelPaths().isEmpty()) {
            if (!extStatus.isEmpty())
                step3.getChildren().add(indented(styledLabel("", MUTED, 4, FontWeight.NORMAL))); // spacer
            step3.getChildren().add(indented(styledLabel("Models:", MUTED, 10, FontWeight.BOLD)));
            for (Map.Entry<String, String> modelEntry : info.modelPaths().entrySet()) {
                String modelName = modelEntry.getKey();
                String originalPath = modelEntry.getValue();
                QTraceExtensionHandler.ModelStatus status = ExtensionHandlerRegistry.get()
                    .findByName("instanseg")
                    .map(h -> h.getModelStatus(modelName, originalPath))
                    .orElse(QTraceExtensionHandler.ModelStatus.MISSING);
                String text = switch (status) {
                    case PRESENT -> "✅  " + modelName;
                    case VERSION_MISMATCH -> {
                        String sourceVersion = InstanSegHandler.lastPathSegment(originalPath);
                        String localVersion = ExtensionHandlerRegistry.get()
                            .findByName("instanseg")
                            .map(h -> h.findLocalModelVersion(modelName, originalPath))
                            .orElse(null);
                        yield "⚠️  " + modelName + "   — source: " + (sourceVersion != null ? sourceVersion : modelName)
                            + "  vs. local: " + (localVersion != null ? localVersion : "unknown")
                            + "  (results may differ)";
                    }
                    case MISSING -> "⚠️  " + modelName + "   — download via Extensions › InstanSeg › Download models";
                };
                step3.getChildren().add(indented(styledLabel(
                    text, status == QTraceExtensionHandler.ModelStatus.PRESENT ? GREEN : YELLOW,
                    11, FontWeight.NORMAL)));
            }
        }

        steps.getChildren().add(step3);

        // Warning banner
        if (!allExtsOk) {
            boolean anyScriptable = extStatus.entrySet().stream()
                .filter(e -> !e.getValue())
                .anyMatch(e -> {
                    QTraceExtensionHandler ke = findKnownExt(e.getKey());
                    return ke == null || ke.getCompat() != Compat.NOT_SCRIPTABLE;
                });
            if (anyScriptable) {
                Label warn = styledLabel(
                    "⚠️  Some extensions are missing. Affected steps may fail.",
                    YELLOW, 11, FontWeight.NORMAL);
                warn.setWrapText(true);
                warn.setPadding(new Insets(4, 0, 0, 0));
                steps.getChildren().add(warn);
            }
        }

        // Step 4 — Run
        steps.getChildren().add(stepRow("4", "Review the script  &  click ▶ Play", null, null));

        // Open Script Editor button
        Button openBtn = new Button("Open Script Editor  ▶");
        openBtn.setFont(Font.font("System", FontWeight.BOLD, 13));
        openBtn.setTextFill(Color.web(TEXT));
        String btnStyle = "-fx-background-color: " + BLUE + "; -fx-background-radius: 4;"
            + " -fx-cursor: hand; -fx-padding: 7 16 7 16;";
        String btnHover = "-fx-background-color: #74c7ec; -fx-background-radius: 4;"
            + " -fx-cursor: hand; -fx-padding: 7 16 7 16;";
        openBtn.setStyle(btnStyle);
        openBtn.setOnMouseEntered(e -> openBtn.setStyle(btnHover));
        openBtn.setOnMouseExited(e -> openBtn.setStyle(btnStyle));
        openBtn.setOnAction(e -> { dialog.close(); parentStage.close(); onConfirm.run(); });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox btnRow = new HBox(spacer, openBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14, header, separator(), steps, separator(), btnRow);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: " + BG + ";");
        root.setMinWidth(480);
        root.setMaxWidth(600);

        dialog.setScene(new Scene(root));
        dialog.show();
    }

    // ── Missing extension row ─────────────────────────────────────────────────

    private HBox missingExtRow(String name, String suffix, QTraceExtensionHandler ke) {
        boolean installable = ke == null || ke.getCompat() != Compat.NOT_SCRIPTABLE;
        String compatTag    = ke != null ? "  " + ke.getCompat().label : "";
        String color        = ke != null ? ke.getCompat().color : YELLOW;

        Label lbl = withTooltip(
            styledLabel("⚠️  " + name + suffix + compatTag, color, 11, FontWeight.NORMAL),
            tooltipFor(ke, ke != null ? ke.getCompat() : null));
        lbl.setWrapText(true);
        HBox.setHgrow(lbl, Priority.ALWAYS);

        HBox row = new HBox(4, lbl);
        row.setAlignment(Pos.CENTER_LEFT);

        if (installable) {
            Button installBtn = new Button("Install →");
            installBtn.setFont(Font.font("System", FontWeight.BOLD, 10));
            installBtn.setTextFill(Color.web(TEXT));
            String s  = "-fx-background-color: #45475a; -fx-background-radius: 3; -fx-cursor: hand; -fx-padding: 2 8 2 8;";
            String sh = "-fx-background-color: #585b70; -fx-background-radius: 3; -fx-cursor: hand; -fx-padding: 2 8 2 8;";
            installBtn.setStyle(s);
            installBtn.setOnMouseEntered(e -> installBtn.setStyle(sh));
            installBtn.setOnMouseExited(e -> installBtn.setStyle(s));
            installBtn.setOnAction(e -> openInstallPage(name, ke, installBtn));
            row.getChildren().add(installBtn);
        } else {
            Label note = styledLabel("(interactive — steps not replayed)", MUTED, 10, FontWeight.NORMAL);
            row.getChildren().add(note);
        }
        return row;
    }

    private void openInstallPage(String extensionName, QTraceExtensionHandler ke, Button btn) {
        String url = ke != null ? ke.getInstallUrl() : FALLBACK_URL;
        btn.setText("Opening…");
        btn.setDisable(true);
        // Never use java.awt.Desktop here: initializing AWT inside the JavaFX/GTK
        // process can crash the JVM on Linux. xdg-open/open/cmd start don't touch AWT.
        new Thread(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("linux"))
                    pb = new ProcessBuilder("xdg-open", url);
                else if (os.contains("mac"))
                    pb = new ProcessBuilder("open", url);
                else
                    pb = new ProcessBuilder("cmd", "/c", "start", url);
                pb.start();
            } catch (Exception ignored) {}
            Platform.runLater(() -> { btn.setText("Install →"); btn.setDisable(false); });
        }, "qtrace-install-browse").start();
    }

    private static QTraceExtensionHandler findKnownExt(String name) {
        String lower = name.toLowerCase().replaceAll("[\\s_-]+", "");
        return ExtensionHandlerRegistry.get().all().stream()
            .filter(h -> lower.contains(h.getExtensionName()) || h.getExtensionName().contains(lower))
            .findFirst()
            .orElse(null);
    }

    // ── QuPath introspection ──────────────────────────────────────────────────

    private static String getCurrentProjectName(QuPathGUI qupath) {
        try {
            var project = qupath.getProject();
            return project != null ? project.getName() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> getLoadedExtensionNames(QuPathGUI qupath) {
        // QuPath 0.5.x
        try {
            Method m = QuPathGUI.class.getMethod("getLoadedExtensions");
            Collection<?> exts = (Collection<?>) m.invoke(qupath);
            List<String> names = toNameList(exts);
            if (!names.isEmpty()) return names;
        } catch (Exception ignored) {}

        // QuPath 0.7.x: ServiceLoader via extension classloader
        try {
            ClassLoader cl = QTracePreflightDialog.class.getClassLoader();
            Class<?> extClass = Class.forName("qupath.lib.gui.extensions.QuPathExtension", false, cl);
            @SuppressWarnings({"unchecked", "rawtypes"})
            java.util.ServiceLoader<?> sl = java.util.ServiceLoader.load((Class) extClass, cl);
            List<Object> list = new java.util.ArrayList<>();
            sl.forEach(list::add);
            return toNameList(list);
        } catch (Exception ignored) {}

        return Collections.emptyList();
    }

    private static List<String> toNameList(Iterable<?> exts) {
        List<String> names = new java.util.ArrayList<>();
        for (Object ext : exts) {
            try {
                String name = (String) ext.getClass().getMethod("getName").invoke(ext);
                if (name != null && !name.isBlank()) names.add(name);
            } catch (Exception ignored) {}
        }
        return names;
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private HBox stepRow(String num, String label, String value, String valueColor) {
        Label numLbl = styledLabel(num + " ›", MUTED, 11, FontWeight.BOLD);
        numLbl.setMinWidth(22);
        Label labelLbl = styledLabel(label, TEXT, 12, FontWeight.BOLD);
        HBox row = new HBox(8, numLbl, labelLbl);
        row.setAlignment(Pos.CENTER_LEFT);
        if (value != null && !value.isBlank()) {
            Label valLbl = styledLabel(value, valueColor != null ? valueColor : TEXT, 12, FontWeight.NORMAL);
            valLbl.setWrapText(true);
            row.getChildren().add(valLbl);
        }
        return row;
    }

    private HBox indented(Label content) {
        Region pad = new Region();
        pad.setMinWidth(30);
        HBox row = new HBox(pad, content);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private HBox indentedRow(HBox content) {
        Region pad = new Region();
        pad.setMinWidth(30);
        HBox row = new HBox(pad, content);
        HBox.setHgrow(content, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String tooltipFor(QTraceExtensionHandler ke, Compat compat) {
        if (ke == null || compat == Compat.FULL) return null;
        String caveat = ke.getCaveat();
        return caveat.isBlank() ? compat.tooltip : caveat;
    }

    /**
     * An extension's Compat is normally a fixed rating for the extension category (e.g. InstanSeg
     * is always "Partial" because it usually needs a model download). But if this specific .qtrace
     * names models we've already verified are present and resolvable on this machine, the caveat
     * that earned it "Partial" no longer applies to this run — so show it as Full instead of a
     * generic warning that never changes regardless of what we actually checked.
     */
    private static Compat effectiveCompat(QTraceExtensionHandler ke, QTraceReplayEngine.PreflightInfo info) {
        if ("instanseg".equalsIgnoreCase(ke.getExtensionName()) && !info.modelPaths().isEmpty()) {
            // Only an exact-version match earns Full — a version mismatch is still usable but is
            // a real caveat (results may differ), so it must keep the extension's normal Partial
            // rating rather than being upgraded away.
            boolean allExact = info.modelPaths().entrySet().stream()
                .allMatch(e -> ke.getModelStatus(e.getKey(), e.getValue()) == QTraceExtensionHandler.ModelStatus.PRESENT);
            if (allExact) return Compat.FULL;
        }
        return ke.getCompat();
    }

    private Compat effectiveCompat(QTraceExtensionHandler ke) { return effectiveCompat(ke, info); }

    private static Label withTooltip(Label label, String text) {
        if (text != null && !text.isBlank()) {
            Tooltip t = new Tooltip(text);
            t.setWrapText(true);
            t.setMaxWidth(320);
            t.setStyle("-fx-font-size: 11;");
            label.setTooltip(t);
        }
        return label;
    }

    private static Label styledLabel(String text, String color, int size, FontWeight weight) {
        Label l = new Label(text);
        l.setTextFill(Color.web(color));
        l.setFont(Font.font("System", weight, size));
        return l;
    }

    private static Separator separator() {
        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: " + BORDER + ";");
        return sep;
    }
}
