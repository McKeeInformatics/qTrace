package io.astraebio.qtrace;

import com.google.gson.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

import io.astraebio.qtrace.chain.CanonicalJson;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;

/**
 * Floating two-pane dashboard.
 *
 * Reads .qtrace format 2.0 (incremental sessions).
 * Image identity is at root.image; all session data is in root.sessions[].
 * Detail cards always show data from the latest session.
 * The Annotations card aggregates across all sessions (timeline + cumulative totals).
 */
public class QTraceDashboard {

    // ── Catppuccin Mocha ──────────────────────────────────────────────────────
    private static final String BG_BASE    = "#1e1e2e";
    private static final String BG_SURFACE = "#181825";
    private static final String BG_CARD    = "#24273a";
    private static final String BORDER     = "#313244";
    private static final String TEXT_MAIN  = "#cdd6f4";
    private static final String TEXT_SUB   = "#a6adc8";
    private static final String TEXT_MUTED = "#6c7086";
    private static final String BLUE       = "#89b4fa";
    private static final String GREEN      = "#a6e3a1";
    private static final String PEACH      = "#fab387";
    private static final String RED        = "#f38ba8";
    private static final String ROW_ALT    = "#20202e";

    private static final Set<String> SEG_KEYWORDS = Set.of(
        "detect", "segment", "instantseg", "stardist", "mesmer",
        "deepcell", "nucleus", "cell", "expand", "watershed", "object"
    );

    private static final String[] COL_NAMES = {
        "Sample", "ROI", "Status", "Region", "BV (aSMA+)", "Tau", "✓ Validated",
        "Alignment", "Segmentation", "Classifiers", "Steps", "🛡"
    };

    // ── Resizable column widths ───────────────────────────────────────────────
    private final SimpleDoubleProperty[] colWidths = {
        new SimpleDoubleProperty(70),   // Sample
        new SimpleDoubleProperty(52),   // ROI
        new SimpleDoubleProperty(110),  // Status
        new SimpleDoubleProperty(86),   // Region
        new SimpleDoubleProperty(74),   // BV (aSMA+)
        new SimpleDoubleProperty(52),   // Tau
        new SimpleDoubleProperty(162),  // ✓ Validated
        new SimpleDoubleProperty(126),  // Alignment
        new SimpleDoubleProperty(88),   // Segmentation
        new SimpleDoubleProperty(156),  // Classifiers
        new SimpleDoubleProperty(52),   // Steps
        new SimpleDoubleProperty(78),   // 🛡 Signature
    };
    private final Label[] sortIndicators = new Label[COL_NAMES.length];

    // ── Row data model ────────────────────────────────────────────────────────
    private record RowData(String imageName, JsonObject qtrace, File qtraceFile) {}

    // ── State ─────────────────────────────────────────────────────────────────
    private final QuPathGUI     qupath;
    private final Stage         stage;
    private final List<RowData> allRows      = new ArrayList<>();
    private final List<RowData> filteredRows = new ArrayList<>();
    private final VBox          tableRows;
    private final Label         scanPathLabel;
    private HBox     selectedRow;
    private RowData  selectedData;
    private boolean  sortAsc     = true;
    private int     sortColIdx  = 0;
    private String  filterText  = "";

    // ── Filter state ──────────────────────────────────────────────────────────
    private final Set<String> filterProjects     = new LinkedHashSet<>();
    private final Set<String> filterContributors = new LinkedHashSet<>();
    private final Set<String> filterClasses      = new LinkedHashSet<>();
    private String  filterAnnotations = "all";
    private boolean filterBV          = false;
    private boolean filterTau         = false;
    private final Set<String> filterStatus = new LinkedHashSet<>();
    private final Set<String> availProjects     = new LinkedHashSet<>();
    private final Set<String> availContributors = new LinkedHashSet<>();
    private final Set<String> availClasses      = new LinkedHashSet<>();
    private VBox      filterCheckboxContainer;
    private TextField filterSearchField;
    private VBox      filterPanelNode;
    private Region    filterPanelSep;
    private Button    filterToggleBtn;

    // ── Detail card containers ────────────────────────────────────────────────
    private final VBox imageCardContent;
    private final VBox alignmentCardContent;
    private final VBox segmentationCardContent;
    private final VBox classifiersCardContent;
    private final VBox cellIntensityCardContent;
    private final VBox annotationsCardContent;

    // ─────────────────────────────────────────────────────────────────────────

    public QTraceDashboard(QuPathGUI qupath) {
        this.qupath = qupath;
        stage = new Stage();
        // No initOwner — owned stages have no taskbar entry on Windows, making
        // the native minimize button non-functional (window vanishes).
        stage.initModality(Modality.NONE);
        stage.setTitle("QTrace Dashboard  v" + QTraceController.VERSION);
        javafx.scene.image.Image logo = QTracePanel.loadLogo();
        if (logo != null) stage.getIcons().add(logo);
        stage.setResizable(true);
        stage.setMinWidth(750);
        stage.setMinHeight(500);
        stage.setWidth(1180);
        stage.setHeight(820);

        tableRows     = new VBox(0);
        scanPathLabel = lbl("", TEXT_MUTED, 10, FontWeight.NORMAL, true);

        imageCardContent         = new VBox(6);
        alignmentCardContent     = new VBox(6);
        segmentationCardContent  = new VBox(6);
        classifiersCardContent   = new VBox(6);
        cellIntensityCardContent = new VBox(6);
        annotationsCardContent   = new VBox(6);

        stage.setScene(new Scene(buildRoot()));
        clearDetail();
        autoScan();
    }

    // ── Root layout ───────────────────────────────────────────────────────────

    private VBox buildRoot() {
        VBox root = new VBox(8);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color:" + BG_BASE + ";");

        filterToggleBtn = new Button("☰");
        filterToggleBtn.setFont(Font.font("System", 14));
        filterToggleBtn.setTextFill(Color.web(BLUE));
        filterToggleBtn.setTooltip(new Tooltip("Toggle filter panel"));
        filterToggleBtn.setStyle(
            "-fx-background-color:transparent;-fx-cursor:hand;-fx-padding:2 6 2 6;");
        filterToggleBtn.setOnMouseEntered(e -> filterToggleBtn.setStyle(
            "-fx-background-color:" + BG_CARD + ";-fx-cursor:hand;-fx-padding:2 6 2 6;"));
        filterToggleBtn.setOnMouseExited(e -> filterToggleBtn.setStyle(
            "-fx-background-color:transparent;-fx-cursor:hand;-fx-padding:2 6 2 6;"));
        filterToggleBtn.setOnAction(e ->
            setFilterPanelVisible(!filterPanelNode.isVisible()));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox header = new HBox(8,
            QTracePanel.logoView(28),
            lbl("📊 QTrace Dashboard", TEXT_MAIN, 15, FontWeight.BOLD, false),
            sp,
            filterToggleBtn,
            buildRefreshButton()
        );
        header.setAlignment(Pos.CENTER_LEFT);

        VBox right = buildRightContent();
        HBox.setHgrow(right, Priority.ALWAYS);

        filterPanelNode = buildFilterPanel();

        filterPanelSep = new Region();
        filterPanelSep.setMinWidth(1);
        filterPanelSep.setMaxWidth(1);
        filterPanelSep.setStyle("-fx-background-color:" + BORDER + ";");

        HBox mainArea = new HBox(0, filterPanelNode, filterPanelSep, right);
        VBox.setVgrow(mainArea, Priority.ALWAYS);

        root.getChildren().addAll(header, scanPathLabel, sep(), mainArea);
        return root;
    }

    private void setFilterPanelVisible(boolean visible) {
        if (filterPanelNode == null) return;
        filterPanelNode.setVisible(visible);
        filterPanelNode.setManaged(visible);
        if (filterPanelSep != null) {
            filterPanelSep.setVisible(visible);
            filterPanelSep.setManaged(visible);
        }
        if (filterToggleBtn != null)
            filterToggleBtn.setTextFill(Color.web(visible ? BLUE : TEXT_MUTED));
    }

    private VBox buildRightContent() {
        ScrollPane detailScroll = buildDetailScroll();
        VBox.setVgrow(detailScroll, Priority.ALWAYS);
        VBox right = new VBox(8);
        right.setPadding(new Insets(0, 0, 0, 10));
        VBox.setVgrow(right, Priority.ALWAYS);
        right.getChildren().addAll(buildTableSection(), sep(), detailScroll);
        return right;
    }

    // ── Toolbar buttons ───────────────────────────────────────────────────────

    private Button buildRefreshButton() {
        Button btn = styledBtn("Refresh", BLUE);
        btn.setOnAction(e -> autoScan());
        return btn;
    }

    private Button styledBtn(String text, String color) {
        Button btn = new Button(text);
        String base  = "-fx-background-color:" + color + ";-fx-text-fill:" + BG_BASE + ";"
            + "-fx-font-weight:bold;-fx-font-size:11;-fx-padding:6 12 6 12;"
            + "-fx-background-radius:4;";
        String hover = "-fx-background-color:derive(" + color + ",-15%);-fx-text-fill:" + BG_BASE + ";"
            + "-fx-font-weight:bold;-fx-font-size:11;-fx-padding:6 12 6 12;"
            + "-fx-background-radius:4;";
        btn.setStyle(base);
        btn.setOnMouseEntered(ev -> btn.setStyle(hover));
        btn.setOnMouseExited(ev  -> btn.setStyle(base));
        return btn;
    }

    // ── Table section ─────────────────────────────────────────────────────────

    private VBox buildTableSection() {
        VBox section = new VBox(0);
        section.setStyle(
            "-fx-background-color:" + BG_SURFACE + ";"
            + "-fx-border-color:" + BORDER + ";"
            + "-fx-border-radius:6;-fx-background-radius:6;");

        tableRows.setStyle("-fx-background-color:" + BG_SURFACE + ";");

        ScrollPane tableScroll = new ScrollPane(tableRows);
        tableScroll.setFitToWidth(false);
        tableScroll.setMinHeight(140);   // ~5 rows always visible
        tableScroll.setPrefHeight(210);
        tableScroll.setMaxHeight(210);
        tableScroll.setStyle(
            "-fx-background-color:" + BG_SURFACE + ";"
            + "-fx-background:" + BG_SURFACE + ";");

        VBox.setVgrow(section, Priority.NEVER);
        section.setMinHeight(170);       // header + 5 rows

        section.getChildren().addAll(buildTableHeaderRow(), tableScroll);
        return section;
    }

    private HBox buildTableHeaderRow() {
        HBox header = new HBox(0);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 8, 0, 8));
        header.setStyle(
            "-fx-background-color:" + BG_CARD + ";"
            + "-fx-border-color:transparent transparent " + BORDER + " transparent;");

        for (int i = 0; i < COL_NAMES.length; i++) {
            header.getChildren().add(buildHeaderCell(i));
        }
        return header;
    }

    private HBox buildHeaderCell(int colIdx) {
        Label nameLbl = new Label(COL_NAMES[colIdx]);
        nameLbl.setTextFill(Color.web(TEXT_MUTED));
        nameLbl.setFont(Font.font("System", FontWeight.BOLD, 10));
        nameLbl.setCursor(Cursor.HAND);

        Label sortLbl = new Label(colIdx == sortColIdx ? (sortAsc ? " ▲" : " ▼") : "");
        sortLbl.setTextFill(Color.web(BLUE));
        sortLbl.setFont(Font.font("System", FontWeight.BOLD, 9));
        sortLbl.setMinWidth(14);
        sortLbl.setCursor(Cursor.HAND);
        sortIndicators[colIdx] = sortLbl;

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);

        Region handle = new Region();
        handle.setMinWidth(5);
        handle.setMaxWidth(5);
        handle.setCursor(Cursor.H_RESIZE);
        handle.setStyle("-fx-background-color:" + BORDER + ";-fx-opacity:0.4;");

        HBox cell = new HBox(2, nameLbl, sortLbl, spring, handle);
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.setPadding(new Insets(5, 2, 5, 0));
        cell.prefWidthProperty().bind(colWidths[colIdx]);
        cell.minWidthProperty().bind(colWidths[colIdx]);
        cell.maxWidthProperty().bind(colWidths[colIdx]);

        final int ci = colIdx;
        cell.setOnMouseClicked(e -> {
            if (e.getTarget() == handle) return;
            if (sortColIdx == ci) sortAsc = !sortAsc;
            else { sortColIdx = ci; sortAsc = true; }
            for (int i = 0; i < sortIndicators.length; i++) {
                if (sortIndicators[i] != null)
                    sortIndicators[i].setText(i == sortColIdx ? (sortAsc ? " ▲" : " ▼") : "");
            }
            applySort();
            renderRows();
        });

        final double[] drag = {0, 0};
        handle.setOnMousePressed(e -> {
            drag[0] = e.getScreenX();
            drag[1] = colWidths[colIdx].get();
        });
        handle.setOnMouseDragged(e ->
            colWidths[colIdx].set(Math.max(40, drag[1] + e.getScreenX() - drag[0])));
        handle.setOnMouseClicked(e -> e.consume());

        return cell;
    }

    // ── Detail section ────────────────────────────────────────────────────────

    private ScrollPane buildDetailScroll() {
        VBox detail = new VBox(10);
        detail.setStyle("-fx-background-color:" + BG_BASE + ";");
        detail.getChildren().addAll(
            wrapCard("🖼  Image & Validation",              imageCardContent),
            wrapCard("🔗  Alignement",                     alignmentCardContent),
            wrapCard("🔬  Segmentation",                   segmentationCardContent),
            wrapCard("🧠  Classifiers Pixel",              classifiersCardContent),
            wrapCard("📊  Cell Intensity Classifications", cellIntensityCardContent),
            wrapCard("👤  Annotations",                    annotationsCardContent)
        );
        ScrollPane scroll = new ScrollPane(detail);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:" + BG_BASE + ";-fx-background:" + BG_BASE + ";");
        return scroll;
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void autoScan() {
        loadAllRows();
        extractFilterMeta();
        rebuildFilterCheckboxes();
        applyFilter();
        applySort();
        renderRows();
    }

    private void loadAllRows() {
        allRows.clear();

        File qtDir = null;
        try { qtDir = QTraceConfig.get().getExportDir().toFile(); } catch (Exception ignored) {}

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
                        JsonObject img = jsonObj(root, "image");
                        String imgName = img != null
                            ? str(img, "name", f.getName().replace(".qtrace", ""))
                            : f.getName().replace(".qtrace", "");
                        qtraceMap.put(imgName, root);
                        fileMap.put(imgName, f);
                    } catch (Exception ignored) {}
                }
            }
        }

        Set<String> added = new LinkedHashSet<>();
        try {
            var project = qupath.getProject();
            if (project != null) {
                for (var entry : project.getImageList()) {
                    String name = entry.getImageName();
                    added.add(name);
                    allRows.add(new RowData(name, findMatchingQtrace(name, qtraceMap),
                        findMatchingFile(name, fileMap)));
                }
            }
        } catch (Exception ignored) {}

        for (var e : qtraceMap.entrySet()) {
            boolean already = added.stream().anyMatch(n -> namesMatch(n, e.getKey()));
            if (!already) allRows.add(new RowData(e.getKey(), e.getValue(), fileMap.get(e.getKey())));
        }

        String dirPath = qtDir != null ? qtDir.getAbsolutePath() : "(not configured)";
        scanPathLabel.setText("📁  " + dirPath
            + "  (" + qtraceMap.size() + " .qtrace  •  " + allRows.size() + " image(s))");
    }

    private JsonObject findMatchingQtrace(String name, Map<String, JsonObject> map) {
        for (var e : map.entrySet())
            if (namesMatch(name, e.getKey())) return e.getValue();
        return null;
    }

    private File findMatchingFile(String name, Map<String, File> map) {
        for (var e : map.entrySet())
            if (namesMatch(name, e.getKey())) return e.getValue();
        return null;
    }

    private boolean namesMatch(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    // ── Filter / sort / render ────────────────────────────────────────────────

    private void applyFilter() {
        filteredRows.clear();
        String f = filterText == null ? "" : filterText.toLowerCase();

        for (RowData rd : allRows) {
            // Text filter — image name + annotation names/descriptions/classes
            if (!f.isEmpty()) {
                boolean textMatch = rd.imageName().toLowerCase().contains(f);
                if (!textMatch && rd.qtrace() != null)
                    textMatch = searchInQtrace(rd.qtrace(), f);
                if (!textMatch) continue;
            }

            // Project filter (empty = all)
            if (!filterProjects.isEmpty()
                    && Collections.disjoint(filterProjects, getProjectsFromRow(rd))) continue;

            // Contributor filter
            if (!filterContributors.isEmpty()
                    && Collections.disjoint(filterContributors, getContributorsFromRow(rd))) continue;

            // Annotation class filter
            if (!filterClasses.isEmpty()
                    && Collections.disjoint(filterClasses, getClassesFromRow(rd))) continue;

            // Annotations presence
            if ("with".equals(filterAnnotations)    && !hasAnnotations(rd)) continue;
            if ("without".equals(filterAnnotations) &&  hasAnnotations(rd)) continue;

            // BV / Tau quick filters
            if (filterBV  && !hasBvFromRow(rd))  continue;
            if (filterTau && !hasTauFromRow(rd)) continue;

            // Status filter
            if (!filterStatus.isEmpty()) {
                String rowStatus = (rd.qtrace() != null && rd.qtrace().has("status"))
                    ? rd.qtrace().get("status").getAsString() : null;
                String key = rowStatus != null ? rowStatus : "none";
                if (!filterStatus.contains(key)) continue;
            }

            filteredRows.add(rd);
        }
    }

    private boolean searchInQtrace(JsonObject root, String query) {
        if (root == null || !root.has("sessions") || root.get("sessions").isJsonNull()) return false;
        for (var el : root.getAsJsonArray("sessions")) {
            JsonObject s = el.getAsJsonObject();
            JsonObject ann = jsonObj(s, "annotations");
            if (ann != null && ann.has("details") && ann.get("details").isJsonArray()) {
                for (var item : ann.getAsJsonArray("details")) {
                    if (!item.isJsonObject()) continue;
                    JsonObject d = item.getAsJsonObject();
                    if (str(d, "name",        "").toLowerCase().contains(query)) return true;
                    if (str(d, "description", "").toLowerCase().contains(query)) return true;
                    if (str(d, "class",       "").toLowerCase().contains(query)) return true;
                }
            }
        }
        return false;
    }

    private Set<String> getProjectsFromRow(RowData rd) {
        Set<String> out = new LinkedHashSet<>();
        JsonObject root = rd.qtrace();
        if (root == null || !root.has("sessions") || root.get("sessions").isJsonNull()) return out;
        for (var el : root.getAsJsonArray("sessions")) {
            JsonObject s = el.getAsJsonObject();
            JsonObject proj = jsonObj(s, "project");
            if (proj != null) {
                String n = str(proj, "name", null);
                if (n != null && !n.isBlank() && !"(no project)".equals(n)) out.add(n);
            }
        }
        return out;
    }

    private Set<String> getContributorsFromRow(RowData rd) {
        Set<String> out = new LinkedHashSet<>();
        JsonObject root = rd.qtrace();
        if (root == null || !root.has("sessions") || root.get("sessions").isJsonNull()) return out;
        for (var el : root.getAsJsonArray("sessions")) {
            JsonObject s = el.getAsJsonObject();
            String user = str(s, "user", null);
            if (user != null && !user.isBlank() && !"unknown".equals(user)) out.add(user);
            JsonObject val = jsonObj(s, "validation");
            if (val != null) {
                String v = str(val, "validator", null);
                if (v != null && !v.isBlank()) out.add(v);
            }
            JsonObject ann = jsonObj(s, "annotations");
            if (ann != null && ann.has("by_author") && ann.get("by_author").isJsonObject()) {
                for (var entry : ann.getAsJsonObject("by_author").entrySet())
                    if (!"unknown".equals(entry.getKey())) out.add(entry.getKey());
            }
        }
        return out;
    }

    private Set<String> getClassesFromRow(RowData rd) {
        Set<String> out = new LinkedHashSet<>();
        // Read latest session only — reflects current annotation state, not historical sessions.
        JsonObject s = latestSession(rd.qtrace());
        if (s == null) return out;
        JsonObject ann = jsonObj(s, "annotations");
        if (ann != null && ann.has("by_class") && ann.get("by_class").isJsonObject())
            for (var entry : ann.getAsJsonObject("by_class").entrySet()) out.add(entry.getKey());
        return out;
    }

    private boolean hasAnnotations(RowData rd) {
        JsonObject root = rd.qtrace();
        if (root == null || !root.has("sessions") || root.get("sessions").isJsonNull()) return false;
        for (var el : root.getAsJsonArray("sessions")) {
            JsonObject s = el.getAsJsonObject();
            JsonObject ann = jsonObj(s, "annotations");
            if (ann != null && ann.has("total") && ann.get("total").getAsInt() > 0) return true;
        }
        return false;
    }

    private void applySort() {
        filteredRows.sort((a, b) -> {
            int cmp = getSortKey(a, sortColIdx).compareToIgnoreCase(getSortKey(b, sortColIdx));
            return sortAsc ? cmp : -cmp;
        });
    }

    private String getSortKey(RowData rd, int colIdx) {
        return switch (colIdx) {
            case 0  -> parseSample(rd.imageName());
            case 1  -> parseRoi(rd.imageName());
            case 2  -> {
                JsonObject r = rd.qtrace();
                yield (r != null && r.has("status_index"))
                    ? String.valueOf(r.get("status_index").getAsInt()) : "9";
            }
            case 3  -> getRegionFromRow(rd);
            case 4  -> hasBvFromRow(rd)  ? "0" : "1";
            case 5  -> hasTauFromRow(rd) ? "0" : "1";
            case 6  -> {
                JsonObject s = latestSession(rd.qtrace());
                if (s == null) yield "z";
                JsonObject val = jsonObj(s, "validation");
                if (val == null) yield "z";
                yield switch (str(val, "confidence", "").toLowerCase()) {
                    case "high"   -> "0";
                    case "medium" -> "1";
                    case "low"    -> "2";
                    default       -> "3";
                };
            }
            default -> rd.imageName();
        };
    }

    private void renderRows() {
        tableRows.getChildren().clear();
        selectedRow = null;
        clearDetail();

        if (filteredRows.isEmpty()) {
            String msg = allRows.isEmpty()
                ? "  No images in project and no .qtrace file"
                : "  No results for \"" + filterText + "\"";
            HBox empty = new HBox(lbl(msg, TEXT_MUTED, 11, FontWeight.NORMAL, true));
            empty.setPadding(new Insets(8));
            tableRows.getChildren().add(empty);
            return;
        }

        boolean alt = false;
        for (RowData data : filteredRows) {
            tableRows.getChildren().add(buildTableRow(data, alt));
            alt = !alt;
        }
    }

    // ── Table row builder ─────────────────────────────────────────────────────

    private HBox buildTableRow(RowData data, boolean alt) {
        JsonObject root    = data.qtrace();
        boolean hasTrace   = root != null;
        String  imageName  = data.imageName();
        JsonObject session = hasTrace ? latestSession(root) : null;
        boolean hasSession = session != null;

        // Col 0 — Sample
        String sample = parseSample(imageName);

        // Col 1 — ROI
        String roi = parseRoi(imageName);

        // Col 2 — Region
        String region    = getRegionFromRow(data);
        boolean hasRegion = !region.isEmpty();

        // Col 3 — BV (aSMA+)
        int    bvCount  = getBvCountFromRow(data);
        String bvText   = bvCount  > 0 ? "●  " + bvCount  : "–";
        String bvColor  = bvCount  > 0 ? GREEN : TEXT_MUTED;

        // Col 4 — Tau
        int    tauCount = getTauCountFromRow(data);
        String tauText  = tauCount > 0 ? "●  " + tauCount : "–";
        String tauColor = tauCount > 0 ? GREEN : TEXT_MUTED;

        // Col 5 — ✓ Validated
        String valText, valColor;
        if (hasSession) {
            JsonObject val = jsonObj(session, "validation");
            if (val != null) {
                String conf = str(val, "confidence", "");
                valText  = "✔  " + truncate(str(val, "validator", "?"), 14) + " — " + conf;
                valColor = confidenceColor(conf);
            } else { valText = "Not validated"; valColor = TEXT_MUTED; }
        } else { valText = "—"; valColor = TEXT_MUTED; }

        // Col 6 — Alignment
        String alignText, alignColor;
        if (hasSession) {
            JsonObject align = jsonObj(session, "alignment");
            boolean applied  = align != null
                && align.has("applied") && align.get("applied").getAsBoolean();
            alignText  = applied ? "✅  " + str(align, "transform_type", "?") : "—";
            alignColor = applied ? GREEN : TEXT_MUTED;
        } else { alignText = "—"; alignColor = TEXT_MUTED; }

        // Col 7 — Segmentation
        String segText, segColor;
        if (hasSession && session.has("steps") && !session.get("steps").isJsonNull()) {
            int cnt = 0;
            for (var el : session.getAsJsonArray("steps")) {
                String cmd = str(el.getAsJsonObject(), "command", "").toLowerCase();
                if (SEG_KEYWORDS.stream().anyMatch(cmd::contains)) cnt++;
            }
            segText  = cnt > 0 ? cnt + " step(s)" : "—";
            segColor = cnt > 0 ? TEXT_SUB : TEXT_MUTED;
        } else { segText = "—"; segColor = TEXT_MUTED; }

        // Col 8 — Classifiers
        String clfText = "—", clfColor = TEXT_MUTED;
        if (hasSession && session.has("pixel_classifiers")
                && !session.get("pixel_classifiers").isJsonNull()) {
            JsonArray clfs = session.getAsJsonArray("pixel_classifiers");
            if (clfs.size() > 0) {
                List<String> parts = new ArrayList<>();
                boolean hasDeg = false, hasComp = false;
                for (var c : clfs) {
                    JsonObject clf = c.getAsJsonObject();
                    String fid = str(clf, "fidelity", "");
                    parts.add(str(clf, "name", "?") + switch (fid) {
                        case "HIGH" -> " ✅"; case "DEGRADED" -> " ⚠"; case "COMPROMISED" -> " ❌"; default -> "";
                    });
                    if ("COMPROMISED".equals(fid)) hasComp = true;
                    if ("DEGRADED".equals(fid))    hasDeg  = true;
                }
                clfText  = truncate(String.join(", ", parts), 26);
                clfColor = hasComp ? RED : hasDeg ? PEACH : GREEN;
            }
        }

        // Col 10 — Steps
        String stepsText = (hasSession && session.has("steps_captured"))
            ? String.valueOf(session.get("steps_captured").getAsInt()) : "—";

        // Col 11 — 🛡 Signature
        String shieldText, shieldColor;
        {
            JsonObject val = hasSession ? jsonObj(session, "validation") : null;
            String sig    = val != null ? str(val, "signature",       "") : "";
            String pubKey = val != null ? str(val, "validatorKeyPub", "") : "";
            if (!sig.isEmpty() && !pubKey.isEmpty()) {
                String vldtr    = str(val, "validator",    "");
                String scope    = str(val, "scope",        "");
                String conf     = str(val, "confidence",   "");
                String ts       = str(val, "timestamp",    "");
                String imgHash  = str(val, "imageHash",    "");
                String stLbl    = str(val, "statusLabel",  "");
                String qpdataSha = str(val, "qpdata_sha256", null);
                if (imgHash.isEmpty()) {
                    JsonObject img = jsonObj(root, "image");
                    if (img != null) imgHash = str(img, "sha256", "");
                }
                if (stLbl.isEmpty() && root != null) stLbl = str(root, "status", "");
                String payload = buildCanonicalPayload(
                    vldtr, scope, conf, stLbl, imgHash, qpdataSha, ts, pubKey);
                boolean ok = StampSigner.verify(payload, pubKey, sig);
                shieldText  = ok ? "signed" : "corrupted";
                shieldColor = ok ? GREEN : RED;
            } else if (val != null && !str(val, "validator", "").isEmpty()) {
                shieldText  = "not signed";
                shieldColor = TEXT_MUTED;
            } else {
                shieldText  = "—";
                shieldColor = TEXT_MUTED;
            }
        }

        String bgDefault = alt
            ? "-fx-background-color:" + ROW_ALT + ";-fx-cursor:hand;"
            : "-fx-background-color:transparent;-fx-cursor:hand;";
        String bgHover = "-fx-background-color:#2a2b3d;-fx-cursor:hand;";

        HBox row = new HBox(0);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5, 8, 5, 8));
        row.setStyle(bgDefault);
        row.getProperties().put("bgDefault", bgDefault);
        row.setUserData(root);

        // Col 2 — Status
        String statusLabel = (root != null && root.has("status"))
            ? root.get("status").getAsString() : null;
        String statusText  = statusLabel != null ? statusIcon(statusLabel) + " " + statusLabel : "—";
        String statusColor = statusLabel != null ? statusColor(statusLabel) : TEXT_MUTED;

        row.getChildren().addAll(
            tcell(sample,    hasTrace ? TEXT_SUB : TEXT_MUTED, 0),
            tcell(roi,       hasTrace ? TEXT_SUB : TEXT_MUTED, 1),
            tcell(statusText, statusColor, 2),
            tcell(hasRegion ? region : "(unknown)", hasRegion ? TEXT_SUB : TEXT_MUTED, 3, !hasRegion),
            tcell(bvText,    bvColor,    4),
            tcell(tauText,   tauColor,   5),
            tcell(valText,   valColor,   6),
            tcell(alignText, alignColor,  7),
            tcell(segText,   segColor,    8),
            tcell(clfText,   clfColor,    9),
            tcell(stepsText,  TEXT_MUTED,  10),
            tcell(shieldText, shieldColor, 11)
        );

        row.setOnMouseEntered(e -> { if (row != selectedRow) row.setStyle(bgHover); });
        row.setOnMouseExited(e  -> { if (row != selectedRow) row.setStyle(bgDefault); });
        row.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (e.getClickCount() == 2) {
                tryOpenImage(imageName);
            } else {
                selectRow(row);
                selectedData = data;
                if (hasTrace) populate(root);
                else clearDetail();
            }
        });

        return row;
    }

    private Label tcell(String text, String color, int colIdx) {
        return tcell(text, color, colIdx, false);
    }

    private Label tcell(String text, String color, int colIdx, boolean italic) {
        Label l = new Label(text);
        l.setTextFill(Color.web(color));
        l.setFont(italic
            ? Font.font("System", FontPosture.ITALIC, 11)
            : Font.font("System", 11));
        l.prefWidthProperty().bind(colWidths[colIdx]);
        l.maxWidthProperty().bind(colWidths[colIdx]);
        l.setMinWidth(0);
        return l;
    }

    private void selectRow(HBox row) {
        if (selectedRow != null) {
            String prev = (String) selectedRow.getProperties().getOrDefault(
                "bgDefault", "-fx-background-color:transparent;-fx-cursor:hand;");
            selectedRow.setStyle(prev);
        }
        selectedRow = row;
        selectedRow.setStyle("-fx-background-color:#313244;-fx-cursor:hand;");
    }

    // ── Open image in QuPath ──────────────────────────────────────────────────

    private void tryOpenImage(String imageName) {
        Platform.runLater(() -> {
            try {
                var project = qupath.getProject();
                if (project == null) return;
                project.getImageList().stream()
                    .filter(e -> namesMatch(e.getImageName(), imageName))
                    .findFirst()
                    .ifPresent(entry -> {
                        try {
                            Method m = findMethod(qupath.getClass(), "openImageEntry");
                            if (m != null) m.invoke(qupath, entry);
                        } catch (Exception ignored) {}
                    });
            } catch (Exception ignored) {}
        });
    }

    @SuppressWarnings("all")
    private static Method findMethod(Class<?> cls, String name) {
        while (cls != null) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name)) { m.setAccessible(true); return m; }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ── Populate detail (format 2.0) ──────────────────────────────────────────

    private void populate(JsonObject root) {
        JsonObject session = latestSession(root);
        JsonObject image   = jsonObj(root, "image");
        buildImageCard(root, image);
        buildAlignmentCard(session);
        buildSegmentationCard(session);
        buildClassifiersCard(session);
        buildCellIntensityCard(session);
        buildAnnotationsCard(root);
    }

    private void clearDetail() {
        selectedData = null;
        setPlaceholder(imageCardContent,         "Select an image from the table above");
        setPlaceholder(alignmentCardContent,     "");
        setPlaceholder(segmentationCardContent,  "");
        setPlaceholder(classifiersCardContent,   "");
        setPlaceholder(cellIntensityCardContent, "");
        setPlaceholder(annotationsCardContent,   "");
    }

    // ── Delete .qtrace ────────────────────────────────────────────────────────

    private void confirmAndDeleteQtrace() {
        if (selectedData == null || selectedData.qtraceFile() == null) return;
        File file = selectedData.qtraceFile();

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("Delete .qtrace file");
        alert.setHeaderText("Permanently delete this provenance file?");
        alert.setContentText(
            "File: " + file.getName() + "\n\n" +
            "This will permanently remove the .qtrace provenance record for:\n" +
            "  " + selectedData.imageName() + "\n\n" +
            "This action cannot be undone.");

        ButtonType deleteType = new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(deleteType, cancelType);

        // Style the Delete button red
        alert.getDialogPane().lookupButton(deleteType)
            .setStyle("-fx-background-color:#c0392b;-fx-text-fill:white;");

        alert.showAndWait().ifPresent(result -> {
            if (result == deleteType) {
                try {
                    java.nio.file.Files.deleteIfExists(file.toPath());
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR,
                        "Could not delete file:\n" + ex.getMessage()).showAndWait();
                    return;
                }
                autoScan();
            }
        });
    }

    // ── Card 1 — Image + Validation ───────────────────────────────────────────

    private void buildImageCard(JsonObject root, JsonObject img) {
        imageCardContent.getChildren().clear();

        if (img != null) {
            Label nameLabel = lbl("🖼  " + str(img, "name", "(inconnu)"), TEXT_MAIN, 13, FontWeight.BOLD, false);
            Button deleteBtn = new Button("🗑 Delete .qtrace");
            deleteBtn.setStyle(
                "-fx-background-color:transparent;-fx-text-fill:#e05252;" +
                "-fx-cursor:hand;-fx-font-size:11;-fx-padding:2 8 2 8;" +
                "-fx-border-color:#e05252;-fx-border-radius:4;-fx-background-radius:4;");
            deleteBtn.setTooltip(new Tooltip("Permanently delete this .qtrace file"));
            deleteBtn.setOnMouseEntered(ev -> deleteBtn.setStyle(
                "-fx-background-color:#3a2020;-fx-text-fill:#e05252;" +
                "-fx-cursor:hand;-fx-font-size:11;-fx-padding:2 8 2 8;" +
                "-fx-border-color:#e05252;-fx-border-radius:4;-fx-background-radius:4;"));
            deleteBtn.setOnMouseExited(ev -> deleteBtn.setStyle(
                "-fx-background-color:transparent;-fx-text-fill:#e05252;" +
                "-fx-cursor:hand;-fx-font-size:11;-fx-padding:2 8 2 8;" +
                "-fx-border-color:#e05252;-fx-border-radius:4;-fx-background-radius:4;"));
            deleteBtn.setOnAction(ev -> confirmAndDeleteQtrace());

            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox titleRow = new HBox(6, nameLabel, sp, deleteBtn);
            titleRow.setAlignment(Pos.CENTER_LEFT);
            imageCardContent.getChildren().add(titleRow);

            String sha = str(img, "sha256", "");
            String shaShort = sha.length() >= 16 ? sha.substring(0, 16) + "…" : sha;
            int w  = img.has("width")    ? img.get("width").getAsInt()    : 0;
            int h  = img.has("height")   ? img.get("height").getAsInt()   : 0;
            int ch = img.has("channels") ? img.get("channels").getAsInt() : 0;
            imageCardContent.getChildren().add(lbl(
                String.format("SHA-256: %s   %d × %d px  •  %d channels  •  %s",
                    shaShort, w, h, ch, str(img, "type", "")),
                TEXT_MUTED, 11, FontWeight.NORMAL, false));
        }

        imageCardContent.getChildren().add(sep());

        JsonArray sessions = (root != null && root.has("sessions") && !root.get("sessions").isJsonNull())
            ? root.getAsJsonArray("sessions") : new JsonArray();

        // Collect validated sessions (chronological) — keep full session object for context
        List<JsonObject> validatedSessions = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            JsonObject s = sessions.get(i).getAsJsonObject();
            if (s.has("validation") && !s.get("validation").isJsonNull())
                validatedSessions.add(s);
        }

        if (validatedSessions.isEmpty()) {
            imageCardContent.getChildren().add(
                lbl("Not validated", TEXT_MUTED, 11, FontWeight.NORMAL, true));
            return;
        }

        // Latest validation always visible
        JsonObject latestSession = validatedSessions.get(validatedSessions.size() - 1);
        imageCardContent.getChildren().add(
            buildValidationRow(latestSession.getAsJsonObject("validation"), root, true));

        if (validatedSessions.size() <= 1) return;

        // History panel (collapsed by default)
        VBox historyBox = new VBox(4);
        historyBox.setVisible(false);
        historyBox.setManaged(false);
        for (int i = validatedSessions.size() - 2; i >= 0; i--) {
            if (i < validatedSessions.size() - 2) historyBox.getChildren().add(sep());
            historyBox.getChildren().add(
                buildValidationRow(validatedSessions.get(i).getAsJsonObject("validation"), root, false));
        }

        // Toggle button
        Button toggleBtn = new Button("▼  " + (validatedSessions.size() - 1) + " previous contributor(s)");
        toggleBtn.setStyle(
            "-fx-background-color: transparent; -fx-border-color: " + BORDER + ";" +
            "-fx-border-radius: 4; -fx-text-fill: " + BLUE + "; -fx-font-size: 11px;" +
            "-fx-cursor: hand; -fx-padding: 2 8 2 8;");
        toggleBtn.setOnAction(e -> {
            boolean nowVisible = !historyBox.isVisible();
            historyBox.setVisible(nowVisible);
            historyBox.setManaged(nowVisible);
            toggleBtn.setText(nowVisible
                ? "▲  Hide history"
                : "▼  " + (validatedSessions.size() - 1) + " previous contributor(s)");
        });

        imageCardContent.getChildren().addAll(toggleBtn, historyBox);
    }

    private VBox buildValidationRow(JsonObject val, JsonObject root, boolean isLatest) {
        String validator  = str(val, "validator",           "?");
        String timestamp  = str(val, "timestamp",           "");
        String scope      = str(val, "scope",               "");
        String confidence = str(val, "confidence",          "");
        String notes      = str(val, "notes",               "");
        String fidelity   = str(val, "classifier_fidelity", "");
        String confColor  = confidenceColor(confidence);

        VBox box = new VBox(3);
        HBox vRow = new HBox(8);
        vRow.setAlignment(Pos.CENTER_LEFT);
        vRow.getChildren().addAll(
            lbl("✔  " + validator, confColor,  12, FontWeight.BOLD,   false),
            lbl("—  " + timestamp, TEXT_MUTED, 11, FontWeight.NORMAL, false)
        );
        box.getChildren().add(vRow);

        HBox details = new HBox(20);
        details.setAlignment(Pos.CENTER_LEFT);
        details.getChildren().addAll(
            kvLbl("Scope",     scope,      TEXT_SUB),
            kvLbl("Confidence", confidence, confColor),
            kvLbl("Fidelity",  fidelity,   fidelityColor(fidelity))
        );
        box.getChildren().add(details);

        if (!notes.isBlank())
            box.getChildren().add(lbl("Notes : \"" + notes + "\"", TEXT_MUTED, 11, FontWeight.NORMAL, true));

        // ── Signature verification (Phase 19.6) ──────────────────────────────
        String signature    = str(val, "signature",       "");
        String validatorKey = str(val, "validatorKeyPub", "");
        if (!signature.isEmpty() && !validatorKey.isEmpty()) {
            String imageHash   = str(val, "imageHash",    "");
            String statusLabel = str(val, "statusLabel",  "");
            String qpdataSha   = str(val, "qpdata_sha256", null);

            // Fallbacks for stamps generated before Phase 19.6
            if (imageHash.isEmpty() && root != null) {
                JsonObject img = jsonObj(root, "image");
                if (img != null) imageHash = str(img, "sha256", "");
            }
            if (statusLabel.isEmpty() && isLatest && root != null)
                statusLabel = str(root, "status", "");

            String payload = buildCanonicalPayload(
                validator, scope, confidence, statusLabel, imageHash, qpdataSha, timestamp, validatorKey);

            boolean valid = StampSigner.verify(payload, validatorKey, signature);
            String badgeText  = valid ? "🔐  Signature ED25519 ✓" : "⚠  Signature invalide";
            String badgeColor = valid ? GREEN : RED;
            Label badge = lbl(badgeText, badgeColor, 11, FontWeight.BOLD, false);
            String vkShort = validatorKey.length() > 20
                ? validatorKey.substring(0, 20) + "…" : validatorKey;
            badge.setTooltip(new Tooltip(valid
                ? "Signature ED25519 valide.\nClé publique : " + vkShort
                : "Signature invalide — le fichier a peut-être été modifié après le stamp."));
            box.getChildren().add(badge);
        }

        return box;
    }

    // ── Card 2 — Alignement ───────────────────────────────────────────────────

    private void buildAlignmentCard(JsonObject session) {
        alignmentCardContent.getChildren().clear();

        JsonObject align = session != null ? jsonObj(session, "alignment") : null;
        boolean applied  = align != null
            && align.has("applied") && align.get("applied").getAsBoolean();

        if (!applied) {
            alignmentCardContent.getChildren().add(
                lbl("No alignment applied", TEXT_MUTED, 11, FontWeight.NORMAL, true));
            return;
        }

        HBox statusRow = new HBox(8);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getChildren().addAll(
            lbl("✅  Applied",                GREEN,      12, FontWeight.BOLD,   false),
            lbl(str(align, "transform_type",  ""), TEXT_SUB,   11, FontWeight.NORMAL, false),
            lbl("via",                         TEXT_MUTED, 11, FontWeight.NORMAL, false),
            lbl(str(align, "capture_source",  ""), BLUE,       11, FontWeight.NORMAL, false)
        );
        alignmentCardContent.getChildren().add(statusRow);

        String moving = str(align, "moving_image_name",    "");
        String ref    = str(align, "reference_image_name", "");
        if (!moving.isEmpty() || !ref.isEmpty()) {
            HBox imgRow = new HBox(6);
            imgRow.setAlignment(Pos.CENTER_LEFT);
            if (!moving.isEmpty())
                imgRow.getChildren().add(lbl("Source: " + moving, TEXT_SUB, 11, FontWeight.NORMAL, false));
            if (!moving.isEmpty() && !ref.isEmpty())
                imgRow.getChildren().add(lbl("→", TEXT_MUTED, 11, FontWeight.NORMAL, false));
            if (!ref.isEmpty())
                imgRow.getChildren().add(lbl("Reference: " + ref, TEXT_SUB, 11, FontWeight.NORMAL, false));
            alignmentCardContent.getChildren().add(imgRow);
        }

        String detBy = str(align, "detected_by", "");
        String detAt = str(align, "detected_at", "");
        if (!detBy.isEmpty())
            alignmentCardContent.getChildren().add(
                lbl("Detected by: " + detBy + "  —  " + detAt, TEXT_MUTED, 11, FontWeight.NORMAL, false));

        GridPane matrix = new GridPane();
        matrix.setHgap(24);
        matrix.setVgap(3);
        matrix.setPadding(new Insets(6, 0, 4, 0));
        addKVRow(matrix, 0, "ScaleX", fmtDbl(align, "scale_x"),     "ScaleY", fmtDbl(align, "scale_y"));
        addKVRow(matrix, 1, "TransX", fmtDbl(align, "translate_x"), "TransY", fmtDbl(align, "translate_y"));
        addKVRow(matrix, 2, "ShearX", fmtDbl(align, "shear_x"),     "ShearY", fmtDbl(align, "shear_y"));
        alignmentCardContent.getChildren().add(matrix);

        JsonObject val    = session != null ? jsonObj(session, "validation") : null;
        String confidence = val != null ? str(val, "confidence", null) : null;
        String precText   = confidence != null
            ? "Validator confidence: " + confidence + "  (from expert stamp)"
            : "Validator confidence: (not validated)";
        alignmentCardContent.getChildren().add(
            lbl(precText, confidence != null ? confidenceColor(confidence) : TEXT_MUTED,
                11, FontWeight.NORMAL, false));
    }

    // ── Card 3 — Segmentation ─────────────────────────────────────────────────

    private void buildSegmentationCard(JsonObject session) {
        segmentationCardContent.getChildren().clear();

        if (session == null || !session.has("steps") || session.get("steps").isJsonNull()) {
            setPlaceholder(segmentationCardContent, "No steps captured");
            return;
        }

        JsonArray allSteps = session.getAsJsonArray("steps");
        List<JsonObject> segSteps = new ArrayList<>();
        for (var el : allSteps) {
            JsonObject step = el.getAsJsonObject();
            String cmd = str(step, "command", "").toLowerCase();
            if (SEG_KEYWORDS.stream().anyMatch(cmd::contains)) segSteps.add(step);
        }

        if (segSteps.isEmpty()) {
            segmentationCardContent.getChildren().add(lbl(
                "No segmentation step identified among the "
                    + allSteps.size() + " captured steps",
                TEXT_MUTED, 11, FontWeight.NORMAL, true));
        } else {
            GridPane grid = new GridPane();
            grid.setHgap(16);
            grid.setVgap(3);
            grid.add(lbl("#",         TEXT_MUTED, 10, FontWeight.BOLD, false), 0, 0);
            grid.add(lbl("Commande",  TEXT_MUTED, 10, FontWeight.BOLD, false), 1, 0);
            grid.add(lbl("Timestamp", TEXT_MUTED, 10, FontWeight.BOLD, false), 2, 0);
            grid.add(lbl("Script",    TEXT_MUTED, 10, FontWeight.BOLD, false), 3, 0);

            for (int i = 0; i < segSteps.size(); i++) {
                JsonObject s = segSteps.get(i);
                int    order = s.has("order") ? s.get("order").getAsInt() : i;
                String cmd   = str(s, "command",   "?");
                String ts    = str(s, "timestamp", "");
                boolean scr  = s.has("is_scriptable") && s.get("is_scriptable").getAsBoolean();
                int row = i + 1;
                grid.add(lbl(String.valueOf(order), TEXT_MUTED, 11, FontWeight.NORMAL, false), 0, row);
                grid.add(lbl(truncate(cmd, 38),     TEXT_SUB,   11, FontWeight.NORMAL, false), 1, row);
                grid.add(lbl(ts.length() > 19 ? ts.substring(0, 19) + "Z" : ts,
                             TEXT_MUTED, 11, FontWeight.NORMAL, false), 2, row);
                grid.add(lbl(scr ? "✓" : "—", scr ? GREEN : TEXT_MUTED, 11, FontWeight.BOLD, false), 3, row);
            }
            segmentationCardContent.getChildren().add(grid);
        }

        JsonObject val = session != null ? jsonObj(session, "validation") : null;
        if (val != null) {
            String conf  = str(val, "confidence",          null);
            String fidel = str(val, "classifier_fidelity", null);
            HBox footer  = new HBox(20);
            footer.setAlignment(Pos.CENTER_LEFT);
            footer.setPadding(new Insets(8, 0, 0, 0));
            if (conf  != null) footer.getChildren().add(kvLbl("Overall quality",      conf,  confidenceColor(conf)));
            if (fidel != null) footer.getChildren().add(kvLbl("Fidelity classifiers", fidel, fidelityColor(fidel)));
            if (!footer.getChildren().isEmpty())
                segmentationCardContent.getChildren().add(footer);
        }
    }

    // ── Card 4 — Classifiers Pixel ────────────────────────────────────────────

    private void buildClassifiersCard(JsonObject session) {
        classifiersCardContent.getChildren().clear();

        JsonArray classifiers = null;
        if (session != null && session.has("pixel_classifiers")
                && !session.get("pixel_classifiers").isJsonNull())
            classifiers = session.getAsJsonArray("pixel_classifiers");

        if (classifiers == null || classifiers.size() == 0) {
            setPlaceholder(classifiersCardContent, "No pixel classifier recorded");
            return;
        }

        boolean first = true;
        for (var el : classifiers) {
            if (!first) classifiersCardContent.getChildren().add(sep());
            classifiersCardContent.getChildren().add(
                buildClassifierSubCard(el.getAsJsonObject()));
            first = false;
        }
    }

    private VBox buildClassifierSubCard(JsonObject clf) {
        String name    = str(clf, "name",             "?");
        String type    = str(clf, "classifier_type",  "");
        String output  = str(clf, "output_type",      "");
        String resUm   = clf.has("resolution_um")
            ? String.format("%.1f µm/px", clf.get("resolution_um").getAsDouble()) : "";
        String savedAt = str(clf, "saved_at",         "");
        String savedBy = str(clf, "saved_by_user",    "");
        String gitHash = str(clf, "git_hash",         "");
        String fidelity= str(clf, "fidelity",         "");
        int nTrain     = clf.has("n_training_regions")
            ? clf.get("n_training_regions").getAsInt() : 0;
        String geoJson = str(clf, "training_geojson", null);
        String tpcFile = str(clf, "tpc_file",         null);

        VBox card = new VBox(4);
        card.setPadding(new Insets(8, 10, 8, 10));
        card.setStyle("-fx-background-color:" + BG_CARD + ";"
            + "-fx-border-color:" + BORDER + ";"
            + "-fx-border-radius:4;-fx-background-radius:4;");

        Region spName = new Region();
        HBox.setHgrow(spName, Priority.ALWAYS);
        HBox nameRow = new HBox(8);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        nameRow.getChildren().add(lbl(name, TEXT_MAIN, 12, FontWeight.BOLD, false));
        if (!fidelity.isEmpty()) {
            String fidIcon = "HIGH".equals(fidelity) ? "✅" : "DEGRADED".equals(fidelity) ? "⚠" : "❌";
            nameRow.getChildren().addAll(spName,
                lbl(fidIcon + "  " + fidelity, fidelityColor(fidelity), 11, FontWeight.BOLD, false));
        }
        card.getChildren().add(nameRow);

        GridPane info = new GridPane();
        info.setHgap(16);
        info.setVgap(3);
        String savedInfo = savedBy
            + (savedAt.length() >= 10 ? "  —  " + savedAt.substring(0, 10) : "");
        addKVRow(info, 0, "Type",       type,  "Sortie",     output);
        addKVRow(info, 1, "Resolution", resUm, "Saved", savedInfo);
        boolean hasGit = !gitHash.isEmpty() && !"null".equals(gitHash);
        if (hasGit)
            addKVRow(info, 2, "Git hash",
                gitHash.length() >= 7 ? gitHash.substring(0, 7) : gitHash, "", "");
        card.getChildren().add(info);

        HBox classesRow  = buildArrayRow(clf, "classes",  "Classes",  "·");
        HBox channelsRow = buildArrayRow(clf, "channels", "Channels", "·");
        if (!classesRow.getChildren().isEmpty())  card.getChildren().add(classesRow);
        if (!channelsRow.getChildren().isEmpty()) card.getChildren().add(channelsRow);

        if (clf.has("features") && clf.get("features").isJsonArray()) {
            List<String> feats = toStrings(clf.getAsJsonArray("features"));
            if (!feats.isEmpty()) {
                String scalesStr = "";
                if (clf.has("scales") && clf.get("scales").isJsonArray()) {
                    List<String> sv = toStrings(clf.getAsJsonArray("scales"));
                    if (!sv.isEmpty()) scalesStr = "  /  scales: " + String.join(", ", sv);
                }
                card.getChildren().add(lbl(
                    "Features: " + String.join(", ", feats) + scalesStr,
                    TEXT_MUTED, 11, FontWeight.NORMAL, false));
            }
        }

        boolean geoOk = geoJson != null && !"null".equals(geoJson) && !geoJson.isBlank();
        HBox trainRow = new HBox(6);
        trainRow.setAlignment(Pos.CENTER_LEFT);
        trainRow.getChildren().addAll(
            lbl("Training: " + nTrain + " regions", TEXT_MUTED, 11, FontWeight.NORMAL, false),
            lbl("→",                                 TEXT_MUTED, 11, FontWeight.NORMAL, false),
            lbl(geoOk ? geoJson : "(not exported)",
                geoOk ? TEXT_SUB : TEXT_MUTED, 11, FontWeight.NORMAL, !geoOk)
        );
        card.getChildren().add(trainRow);

        boolean tpcOk = tpcFile != null && !"null".equals(tpcFile) && !tpcFile.isBlank();
        card.getChildren().add(lbl(
            "TPC file: " + (tpcOk ? tpcFile : "(not exported)"),
            tpcOk ? TEXT_SUB : TEXT_MUTED, 11, FontWeight.NORMAL, !tpcOk));

        return card;
    }

    // ── Card 5 — Cell Intensity Classifications ───────────────────────────────

    private void buildCellIntensityCard(JsonObject session) {
        cellIntensityCardContent.getChildren().clear();

        JsonArray cicArr = null;
        if (session != null && session.has("cell_intensity_classifications")
                && !session.get("cell_intensity_classifications").isJsonNull())
            cicArr = session.getAsJsonArray("cell_intensity_classifications");

        if (cicArr == null || cicArr.size() == 0) {
            setPlaceholder(cellIntensityCardContent,
                "No cell intensity classification recorded");
            return;
        }

        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(4);
        grid.add(lbl("Measurement",   TEXT_MUTED, 10, FontWeight.BOLD, false), 0, 0);
        grid.add(lbl("1+",            TEXT_MUTED, 10, FontWeight.BOLD, false), 1, 0);
        grid.add(lbl("2+",            TEXT_MUTED, 10, FontWeight.BOLD, false), 2, 0);
        grid.add(lbl("3+",            TEXT_MUTED, 10, FontWeight.BOLD, false), 3, 0);
        grid.add(lbl("Applied at",    TEXT_MUTED, 10, FontWeight.BOLD, false), 4, 0);
        grid.add(lbl("By",            TEXT_MUTED, 10, FontWeight.BOLD, false), 5, 0);

        int row = 1;
        for (var el : cicArr) {
            JsonObject cic = el.getAsJsonObject();
            String measurement = str(cic, "measurement", "?");
            String t1 = cic.has("threshold_1plus")
                ? String.format("%.4f", cic.get("threshold_1plus").getAsDouble()) : "—";
            String t2 = cic.has("threshold_2plus")
                ? String.format("%.4f", cic.get("threshold_2plus").getAsDouble()) : "—";
            String t3 = cic.has("threshold_3plus")
                ? String.format("%.4f", cic.get("threshold_3plus").getAsDouble()) : "—";
            String ts  = str(cic, "applied_at", "");
            String tsShort = ts.length() > 19 ? ts.substring(0, 19) : ts;
            String by  = str(cic, "applied_by", "");

            grid.add(lbl(truncate(measurement, 36), TEXT_SUB,   11, FontWeight.NORMAL, false), 0, row);
            grid.add(lbl(t1,                         BLUE,       11, FontWeight.BOLD,   false), 1, row);
            grid.add(lbl(t2,                         TEXT_SUB,   11, FontWeight.NORMAL, false), 2, row);
            grid.add(lbl(t3,                         TEXT_MUTED, 11, FontWeight.NORMAL, false), 3, row);
            grid.add(lbl(tsShort,                    TEXT_MUTED, 11, FontWeight.NORMAL, false), 4, row);
            grid.add(lbl(by,                         TEXT_MUTED, 11, FontWeight.NORMAL, false), 5, row);
            row++;
        }
        cellIntensityCardContent.getChildren().add(grid);
    }

    // ── Card 6 — Annotations ─────────────────────────────────────────────────

    private void buildAnnotationsCard(JsonObject root) {
        annotationsCardContent.getChildren().clear();

        if (!root.has("sessions") || root.get("sessions").isJsonNull()) {
            setPlaceholder(annotationsCardContent, "No session recorded");
            return;
        }
        JsonArray sessions = root.getAsJsonArray("sessions");
        if (sessions.size() == 0) {
            setPlaceholder(annotationsCardContent, "No session recorded");
            return;
        }

        // Totals from last session (authoritative — reflects current state of hierarchy)
        JsonObject lastSession = sessions.get(sessions.size() - 1).getAsJsonObject();
        JsonObject lastAnn = lastSession.has("annotations")
                && !lastSession.get("annotations").isJsonNull()
            ? lastSession.getAsJsonObject("annotations") : null;

        int total = lastAnn != null && lastAnn.has("total") ? lastAnn.get("total").getAsInt() : 0;
        annotationsCardContent.getChildren().add(
            lbl("Total : " + total + " annotation(s)  •  " + sessions.size() + " session(s)",
                TEXT_MAIN, 12, FontWeight.BOLD, false));
        annotationsCardContent.getChildren().add(sep());

        if (lastAnn != null) {
            // By Author
            if (lastAnn.has("by_author") && lastAnn.get("by_author").isJsonObject()) {
                JsonObject byAuthor = lastAnn.getAsJsonObject("by_author");
                if (byAuthor.size() > 0) {
                    annotationsCardContent.getChildren().add(
                        lbl("By author", TEXT_MUTED, 10, FontWeight.BOLD, false));
                    GridPane authorGrid = new GridPane();
                    authorGrid.setHgap(20);
                    authorGrid.setVgap(3);
                    authorGrid.add(lbl("Author", TEXT_MUTED, 10, FontWeight.BOLD, false), 0, 0);
                    authorGrid.add(lbl("Annotations", TEXT_MUTED, 10, FontWeight.BOLD, false), 1, 0);
                    int aRow = 1;
                    for (var entry : byAuthor.entrySet()) {
                        authorGrid.add(lbl(entry.getKey(), BLUE,      11, FontWeight.NORMAL, false), 0, aRow);
                        authorGrid.add(lbl(String.valueOf(entry.getValue().getAsInt()),
                            TEXT_SUB, 11, FontWeight.NORMAL, false), 1, aRow);
                        aRow++;
                    }
                    annotationsCardContent.getChildren().add(authorGrid);
                    annotationsCardContent.getChildren().add(sep());
                }
            }

            // By Class
            if (lastAnn.has("by_class") && lastAnn.get("by_class").isJsonObject()) {
                JsonObject byClass = lastAnn.getAsJsonObject("by_class");
                if (byClass.size() > 0) {
                    annotationsCardContent.getChildren().add(
                        lbl("By class", TEXT_MUTED, 10, FontWeight.BOLD, false));
                    GridPane classGrid = new GridPane();
                    classGrid.setHgap(20);
                    classGrid.setVgap(3);
                    classGrid.add(lbl("Class", TEXT_MUTED, 10, FontWeight.BOLD, false), 0, 0);
                    classGrid.add(lbl("Total",  TEXT_MUTED, 10, FontWeight.BOLD, false), 1, 0);
                    int cRow = 1;
                    for (var entry : byClass.entrySet()) {
                        classGrid.add(lbl(entry.getKey(), TEXT_SUB,   11, FontWeight.NORMAL, false), 0, cRow);
                        classGrid.add(lbl(String.valueOf(entry.getValue().getAsInt()),
                            TEXT_MUTED, 11, FontWeight.NORMAL, false), 1, cRow);
                        cRow++;
                    }
                    annotationsCardContent.getChildren().add(classGrid);
                    annotationsCardContent.getChildren().add(sep());
                }
            }

            // GeoJSON sidecar reference
            String geoFile = str(lastAnn, "geojson_file", null);
            if (geoFile != null && !geoFile.isBlank()) {
                annotationsCardContent.getChildren().add(
                    lbl("GeoJSON : " + geoFile, TEXT_MUTED, 10, FontWeight.NORMAL, false));
                annotationsCardContent.getChildren().add(sep());
            }
        }

        // Sessions timeline
        annotationsCardContent.getChildren().add(
            lbl("Session timeline", TEXT_MUTED, 10, FontWeight.BOLD, false));

        for (int i = 0; i < sessions.size(); i++) {
            JsonObject s       = sessions.get(i).getAsJsonObject();
            String user        = str(s, "user",        "?");
            String machine     = str(s, "machine",     "");
            String exportedAt  = str(s, "exported_at", "");
            String dateShort   = exportedAt.length() >= 10 ? exportedAt.substring(0, 10) : exportedAt;

            JsonObject ann = s.has("annotations") && !s.get("annotations").isJsonNull()
                ? s.getAsJsonObject("annotations") : null;
            int annCount = ann != null && ann.has("added_in_session")
                ? ann.get("added_in_session").getAsInt()
                : (ann != null && ann.has("total") ? ann.get("total").getAsInt() : 0);

            JsonObject val     = s.has("validation") && !s.get("validation").isJsonNull()
                ? s.getAsJsonObject("validation") : null;
            String validStr    = val != null
                ? "✅ " + str(val, "validator", "?") + " (" + str(val, "confidence", "") + ")"
                : "(not validated)";
            String validColor  = val != null ? GREEN : TEXT_MUTED;

            String machineStr  = machine.isEmpty() ? "" : "  @" + machine;

            HBox sessionRow = new HBox(12);
            sessionRow.setAlignment(Pos.CENTER_LEFT);
            sessionRow.setPadding(new Insets(2, 0, 2, 0));
            sessionRow.getChildren().addAll(
                lbl("#" + (i + 1),                   TEXT_MUTED, 10, FontWeight.BOLD,   false),
                lbl(user + machineStr,                BLUE,       11, FontWeight.NORMAL, false),
                lbl(dateShort,                        TEXT_MUTED, 11, FontWeight.NORMAL, false),
                lbl(annCount + " annot.",             TEXT_SUB,   11, FontWeight.NORMAL, false),
                lbl(validStr,                         validColor, 11, FontWeight.NORMAL, false)
            );
            annotationsCardContent.getChildren().add(sessionRow);
        }
    }

    // ── Format 2.0 helper ────────────────────────────────────────────────────

    /** Returns the last session object from a format-2.0 root, or null if absent. */
    private static JsonObject latestSession(JsonObject root) {
        if (root == null || !root.has("sessions") || root.get("sessions").isJsonNull()) return null;
        JsonArray sessions = root.getAsJsonArray("sessions");
        if (sessions.size() == 0) return null;
        return sessions.get(sessions.size() - 1).getAsJsonObject();
    }

    // ── Cohort tracker helpers ────────────────────────────────────────────────

    /** "ROI22_1904-CM-ROI1.ome.tiff - ..." → "19_04" */
    private static String parseSample(String imageName) {
        try {
            String base = imageName.split(" - ")[0].replace(".ome.tiff", "").replace(".tiff", "");
            String[] parts = base.split("_");
            if (parts.length >= 2) {
                String code = parts[1].split("-")[0];
                if (code.length() == 4) return code.substring(0, 2) + "_" + code.substring(2);
                return code;
            }
        } catch (Exception ignored) {}
        return imageName;
    }

    /** "ROI22_1904-CM-ROI1.ome.tiff - ..." → "ROI1" */
    private static String parseRoi(String imageName) {
        try {
            String base = imageName.split(" - ")[0].replace(".ome.tiff", "").replace(".tiff", "");
            String[] parts = base.split("_");
            if (parts.length >= 2 && parts[1].contains("CM-"))
                return parts[1].split("CM-")[1];
        } catch (Exception ignored) {}
        return "—";
    }

    /** First Region*-class annotation whose name is non-empty in the latest session, or "". */
    private String getRegionFromRow(RowData rd) {
        JsonObject session = latestSession(rd.qtrace());
        if (session == null) return "";
        JsonObject ann = jsonObj(session, "annotations");
        if (ann == null || !ann.has("details") || !ann.get("details").isJsonArray()) return "";
        for (var item : ann.getAsJsonArray("details")) {
            if (!item.isJsonObject()) continue;
            JsonObject d = item.getAsJsonObject();
            String cls  = str(d, "class", "");
            if (cls.startsWith("Region")) {
                String name = str(d, "name", "");
                if (!name.isEmpty()) return name;
            }
        }
        return "";
    }

    private int getBvCountFromRow(RowData rd) {
        JsonObject ann = latestAnnotations(rd);
        if (ann == null || !ann.has("by_class") || !ann.get("by_class").isJsonObject()) return 0;
        JsonObject bc = ann.getAsJsonObject("by_class");
        return bc.has("aSMA +") ? bc.get("aSMA +").getAsInt() : 0;
    }

    private int getTauCountFromRow(RowData rd) {
        JsonObject ann = latestAnnotations(rd);
        if (ann == null || !ann.has("by_class") || !ann.get("by_class").isJsonObject()) return 0;
        JsonObject bc = ann.getAsJsonObject("by_class");
        return bc.has("general Tau") ? bc.get("general Tau").getAsInt() : 0;
    }

    private boolean hasBvFromRow(RowData rd)  { return getBvCountFromRow(rd)  > 0; }
    private boolean hasTauFromRow(RowData rd) { return getTauCountFromRow(rd) > 0; }

    private JsonObject latestAnnotations(RowData rd) {
        JsonObject session = latestSession(rd.qtrace());
        return session != null ? jsonObj(session, "annotations") : null;
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private Label lbl(String text, String color, int size, FontWeight weight, boolean italic) {
        Label l = new Label(text);
        l.setTextFill(Color.web(color));
        l.setFont(italic
            ? Font.font("System", FontPosture.ITALIC, size)
            : Font.font("System", weight, size));
        return l;
    }

    private HBox kvLbl(String key, String value, String valueColor) {
        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(
            lbl(key + ":", TEXT_MUTED, 11, FontWeight.NORMAL, false),
            lbl(value,     valueColor, 11, FontWeight.BOLD,   false)
        );
        return row;
    }

    private void addKVRow(GridPane grid, int row, String k1, String v1, String k2, String v2) {
        grid.add(lbl(k1 + ":", TEXT_MUTED, 10, FontWeight.NORMAL, false), 0, row);
        grid.add(lbl(v1,       TEXT_SUB,   11, FontWeight.NORMAL, false), 1, row);
        if (k2 != null && !k2.isEmpty()) {
            grid.add(lbl(k2 + ":", TEXT_MUTED, 10, FontWeight.NORMAL, false), 2, row);
            grid.add(lbl(v2 != null ? v2 : "", TEXT_SUB, 11, FontWeight.NORMAL, false), 3, row);
        }
    }

    private HBox buildArrayRow(JsonObject obj, String field, String label, String delim) {
        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        if (!obj.has(field) || !obj.get(field).isJsonArray()) return row;
        List<String> items = toStrings(obj.getAsJsonArray(field));
        if (items.isEmpty()) return row;
        row.getChildren().addAll(
            lbl(label + ":", TEXT_MUTED, 11, FontWeight.NORMAL, false),
            lbl(String.join("  " + delim + "  ", items), TEXT_SUB, 11, FontWeight.NORMAL, false)
        );
        return row;
    }

    private VBox wrapCard(String title, VBox content) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(12));
        card.setStyle("-fx-background-color:" + BG_SURFACE + ";"
            + "-fx-border-color:" + BORDER + ";"
            + "-fx-border-radius:6;-fx-background-radius:6;");
        card.getChildren().addAll(
            lbl(title, TEXT_MAIN, 12, FontWeight.BOLD, false),
            sep(),
            content
        );
        return card;
    }

    private void setPlaceholder(VBox container, String msg) {
        container.getChildren().clear();
        if (!msg.isEmpty())
            container.getChildren().add(lbl(msg, TEXT_MUTED, 11, FontWeight.NORMAL, true));
    }

    private Separator sep() {
        Separator s = new Separator();
        s.setStyle("-fx-background-color:" + BORDER + ";");
        return s;
    }

    // ── Signature helpers ─────────────────────────────────────────────────────

    /**
     * Reconstructs the RFC 8785 canonical payload for a stamp read from a .qtrace.
     * Mirrors ValidationStamp.canonicalPayload() exactly.
     * qpdataSha256 may be null (stamps created before M0 or without a project).
     */
    private static String buildCanonicalPayload(
            String validator, String scope, String confidence,
            String statusLabel, String imageHash, String qpdataSha256,
            String timestamp, String validatorKeyPub) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("confidence",        confidence     != null ? confidence    : "");
        fields.put("git_hash",          "");            // UI stamps always pass null gitHash
        fields.put("image_sha256",      imageHash      != null ? imageHash     : "");
        fields.put("qpdata_sha256",     qpdataSha256);  // null → JSON null, matches stamp
        fields.put("scope",             scope          != null ? scope         : "");
        fields.put("signing_meaning",   ValidationStamp.SIGNING_MEANING);
        fields.put("status",            statusLabel    != null ? statusLabel   : "");
        fields.put("timestamp",         timestamp      != null ? timestamp     : "");
        fields.put("validator",         validator      != null ? validator     : "");
        fields.put("validator_key_pub", validatorKeyPub != null ? validatorKeyPub : "");
        return CanonicalJson.of(fields);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private String str(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull())
            return def;   // returns null when def is null (used for optional fields)
        return obj.get(key).getAsString();
    }

    private JsonObject jsonObj(JsonObject root, String key) {
        if (root == null || !root.has(key) || root.get(key).isJsonNull()) return null;
        try { return root.getAsJsonObject(key); } catch (Exception e) { return null; }
    }

    private String fmtDbl(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "—";
        try { return String.format("%.6f", obj.get(key).getAsDouble()); }
        catch (Exception e) { return obj.get(key).getAsString(); }
    }

    private List<String> toStrings(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (var el : arr) { try { out.add(el.getAsString()); } catch (Exception ignored) {} }
        return out;
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s != null ? s : "";
        return s.substring(0, max - 1) + "…";
    }

    private String confidenceColor(String confidence) {
        if (confidence == null) return TEXT_MUTED;
        return switch (confidence.toLowerCase()) {
            case "high"   -> GREEN;
            case "medium" -> PEACH;
            case "low"    -> RED;
            default       -> TEXT_SUB;
        };
    }

    private String fidelityColor(String fidelity) {
        if (fidelity == null || fidelity.isEmpty()) return TEXT_MUTED;
        return switch (fidelity.toUpperCase()) {
            case "HIGH"        -> GREEN;
            case "DEGRADED"    -> PEACH;
            case "COMPROMISED" -> RED;
            default            -> TEXT_SUB;
        };
    }

    private String statusColor(String label) {
        if (label == null) return TEXT_MUTED;
        if (label.startsWith("2")) return GREEN;
        if (label.startsWith("1")) return PEACH;
        return TEXT_MUTED;
    }

    private String statusIcon(String label) {
        if (label == null) return "○";
        if (label.startsWith("2")) return "✅";
        if (label.startsWith("1")) return "⏳";
        return "○";
    }

    // ── Filter panel ──────────────────────────────────────────────────────────

    private VBox buildFilterPanel() {
        VBox panel = new VBox(0);
        panel.setMinWidth(200);
        panel.setMaxWidth(200);
        panel.setPrefWidth(200);
        panel.setStyle("-fx-background-color:" + BG_SURFACE + ";");

        Region titleSp = new Region();
        HBox.setHgrow(titleSp, Priority.ALWAYS);

        Button closeBtn = new Button("◀");
        closeBtn.setFont(Font.font("System", 11));
        closeBtn.setTextFill(Color.web(TEXT_MUTED));
        closeBtn.setStyle(
            "-fx-background-color:transparent;-fx-cursor:hand;-fx-padding:2 4 2 4;");
        closeBtn.setTooltip(new Tooltip("Hide filter panel"));
        closeBtn.setOnMouseEntered(e -> closeBtn.setTextFill(Color.web(BLUE)));
        closeBtn.setOnMouseExited(e  -> closeBtn.setTextFill(Color.web(TEXT_MUTED)));
        closeBtn.setOnAction(e -> setFilterPanelVisible(false));

        HBox titleRow = new HBox(0,
            lbl("🔍  Filters", TEXT_MAIN, 12, FontWeight.BOLD, false),
            titleSp, closeBtn);
        titleRow.setPadding(new Insets(8, 8, 8, 12));
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setStyle("-fx-background-color:" + BG_CARD + ";");

        filterSearchField = new TextField(filterText);
        filterSearchField.setPromptText("Search…");
        filterSearchField.setStyle(
            "-fx-control-inner-background:" + BG_CARD + ";"
            + "-fx-background-color:" + BG_CARD + ";"
            + "-fx-text-fill:" + TEXT_MAIN + ";"
            + "-fx-prompt-text-fill:" + TEXT_MUTED + ";"
            + "-fx-border-color:" + BORDER + ";"
            + "-fx-border-radius:4;-fx-background-radius:4;"
            + "-fx-font-size:11;-fx-padding:5 6;");
        HBox.setHgrow(filterSearchField, Priority.ALWAYS);
        filterSearchField.textProperty().addListener((obs, old, val) -> {
            filterText = val == null ? "" : val;
            applyFilter(); applySort(); renderRows();
        });

        Button clearSearch = new Button("✕");
        clearSearch.setStyle(
            "-fx-background-color:transparent;-fx-text-fill:" + TEXT_MUTED + ";"
            + "-fx-font-size:10;-fx-padding:3 5;-fx-cursor:hand;");
        clearSearch.setOnAction(e -> {
            filterSearchField.clear();
            filterText = "";
            applyFilter(); applySort(); renderRows();
        });

        HBox searchRow = new HBox(4, filterSearchField, clearSearch);
        searchRow.setPadding(new Insets(8, 8, 8, 8));
        searchRow.setAlignment(Pos.CENTER_LEFT);

        filterCheckboxContainer = new VBox(0);
        filterCheckboxContainer.setStyle("-fx-background-color:" + BG_SURFACE + ";");

        ScrollPane scroll = new ScrollPane(filterCheckboxContainer);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color:" + BG_SURFACE + ";-fx-background:" + BG_SURFACE + ";");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button clearAll = new Button("Clear all filters");
        clearAll.setMaxWidth(Double.MAX_VALUE);
        clearAll.setStyle(
            "-fx-background-color:" + BG_CARD + ";"
            + "-fx-text-fill:" + PEACH + ";"
            + "-fx-font-size:11;-fx-padding:7 10;"
            + "-fx-cursor:hand;-fx-border-color:" + BORDER + ";"
            + "-fx-border-radius:0;");
        clearAll.setOnAction(e -> {
            filterProjects.clear();
            filterContributors.clear();
            filterClasses.clear();
            filterStatus.clear();
            filterAnnotations = "all";
            filterBV  = false;
            filterTau = false;
            if (filterSearchField != null) filterSearchField.clear();
            filterText = "";
            applyFilter(); applySort(); renderRows();
            rebuildFilterCheckboxes();
        });

        panel.getChildren().addAll(titleRow, sep(), searchRow, sep(), scroll, sep(), clearAll);
        return panel;
    }

    private VBox buildFilterSection(String title, VBox content) {
        final boolean[] open = {true};

        Label titleLbl = lbl(title, TEXT_SUB, 11, FontWeight.BOLD, false);
        Label arrow    = lbl(" ▼", TEXT_MUTED, 9, FontWeight.NORMAL, false);
        Region spring  = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);

        HBox header = new HBox(0, titleLbl, spring, arrow);
        header.setPadding(new Insets(7, 8, 7, 8));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setCursor(Cursor.HAND);
        header.setStyle("-fx-background-color:" + BG_CARD + ";");
        header.setOnMouseClicked(e -> {
            open[0] = !open[0];
            content.setVisible(open[0]);
            content.setManaged(open[0]);
            arrow.setText(open[0] ? " ▼" : " ▶");
        });
        header.setOnMouseEntered(e -> header.setStyle("-fx-background-color:#2a2b3d;"));
        header.setOnMouseExited( e -> header.setStyle("-fx-background-color:" + BG_CARD + ";"));

        content.setPadding(new Insets(5, 8, 8, 12));
        content.setStyle("-fx-background-color:" + BG_SURFACE + ";");

        VBox section = new VBox(0, header, content);
        section.setStyle("-fx-border-color:transparent transparent " + BORDER + " transparent;");
        return section;
    }

    private void extractFilterMeta() {
        availProjects.clear();
        availContributors.clear();
        availClasses.clear();
        for (RowData rd : allRows) {
            if (rd.qtrace() == null) continue;
            availProjects.addAll(getProjectsFromRow(rd));
            availContributors.addAll(getContributorsFromRow(rd));
            availClasses.addAll(getClassesFromRow(rd));
        }
        filterProjects.retainAll(availProjects);
        filterContributors.retainAll(availContributors);
        filterClasses.retainAll(availClasses);
    }

    private void rebuildFilterCheckboxes() {
        if (filterCheckboxContainer == null) return;
        filterCheckboxContainer.getChildren().clear();

        // Projects
        VBox projectsBox = new VBox(3);
        if (availProjects.isEmpty()) {
            projectsBox.getChildren().add(lbl("(none)", TEXT_MUTED, 10, FontWeight.NORMAL, true));
        } else {
            for (String p : availProjects) {
                CheckBox cb = new CheckBox(truncate(p, 22));
                cb.setSelected(filterProjects.contains(p));
                cb.setStyle("-fx-text-fill:" + TEXT_SUB + ";-fx-font-size:11;");
                cb.setOnAction(e -> {
                    if (cb.isSelected()) filterProjects.add(p); else filterProjects.remove(p);
                    applyFilter(); applySort(); renderRows();
                });
                projectsBox.getChildren().add(cb);
            }
        }

        // Contributors
        VBox contribBox = new VBox(3);
        if (availContributors.isEmpty()) {
            contribBox.getChildren().add(lbl("(none)", TEXT_MUTED, 10, FontWeight.NORMAL, true));
        } else {
            for (String c : availContributors) {
                CheckBox cb = new CheckBox(truncate(c, 22));
                cb.setSelected(filterContributors.contains(c));
                cb.setStyle("-fx-text-fill:" + TEXT_SUB + ";-fx-font-size:11;");
                cb.setOnAction(e -> {
                    if (cb.isSelected()) filterContributors.add(c); else filterContributors.remove(c);
                    applyFilter(); applySort(); renderRows();
                });
                contribBox.getChildren().add(cb);
            }
        }

        // Annotation classes
        VBox classesBox = new VBox(3);
        if (availClasses.isEmpty()) {
            classesBox.getChildren().add(lbl("(none)", TEXT_MUTED, 10, FontWeight.NORMAL, true));
        } else {
            for (String cls : availClasses) {
                CheckBox cb = new CheckBox(truncate(cls, 22));
                cb.setSelected(filterClasses.contains(cls));
                cb.setStyle("-fx-text-fill:" + TEXT_SUB + ";-fx-font-size:11;");
                cb.setOnAction(e -> {
                    if (cb.isSelected()) filterClasses.add(cls); else filterClasses.remove(cls);
                    applyFilter(); applySort(); renderRows();
                });
                classesBox.getChildren().add(cb);
            }
        }

        // Annotations presence (radio buttons)
        VBox annBox = new VBox(4);
        ToggleGroup group = new ToggleGroup();
        String[] annValues = {"all", "with", "without"};
        String[] annLabels = {"All", "With annotations", "Without"};
        for (int i = 0; i < annValues.length; i++) {
            RadioButton rb = new RadioButton(annLabels[i]);
            rb.setToggleGroup(group);
            rb.setSelected(filterAnnotations.equals(annValues[i]));
            rb.setStyle("-fx-text-fill:" + TEXT_SUB + ";-fx-font-size:11;");
            final String val = annValues[i];
            rb.setOnAction(e -> { filterAnnotations = val; applyFilter(); applySort(); renderRows(); });
            annBox.getChildren().add(rb);
        }

        // BV & Tau quick filters
        VBox bvTauBox = new VBox(4);
        CheckBox cbBV = new CheckBox("BV annotations (aSMA +)");
        cbBV.setSelected(filterBV);
        cbBV.setStyle("-fx-text-fill:" + TEXT_SUB + ";-fx-font-size:11;");
        cbBV.setOnAction(e -> { filterBV = cbBV.isSelected(); applyFilter(); applySort(); renderRows(); });
        CheckBox cbTau = new CheckBox("Tau annotations");
        cbTau.setSelected(filterTau);
        cbTau.setStyle("-fx-text-fill:" + TEXT_SUB + ";-fx-font-size:11;");
        cbTau.setOnAction(e -> { filterTau = cbTau.isSelected(); applyFilter(); applySort(); renderRows(); });
        bvTauBox.getChildren().addAll(cbBV, cbTau);

        // Status filter
        VBox statusBox = new VBox(4);
        String[] statusKeys   = {"2-Finished", "1-In Progress", "0-To Begin", "none"};
        String[] statusLabels = {"✅ 2-Finished", "⏳ 1-In Progress", "○ 0-To Begin", "○ No status"};
        for (int i = 0; i < statusKeys.length; i++) {
            String key   = statusKeys[i];
            CheckBox cb  = new CheckBox(statusLabels[i]);
            cb.setSelected(filterStatus.contains(key));
            cb.setStyle("-fx-text-fill:" + TEXT_SUB + ";-fx-font-size:11;");
            cb.setOnAction(e -> {
                if (cb.isSelected()) filterStatus.add(key); else filterStatus.remove(key);
                applyFilter(); applySort(); renderRows();
            });
            statusBox.getChildren().add(cb);
        }

        filterCheckboxContainer.getChildren().addAll(
            buildFilterSection("Status",             statusBox),
            buildFilterSection("BV & Tau",           bvTauBox),
            buildFilterSection("Projects",           projectsBox),
            buildFilterSection("Contributors",       contribBox),
            buildFilterSection("Annotation Classes", classesBox),
            buildFilterSection("Annotations",        annBox)
        );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void show()       { stage.show(); stage.toFront(); stage.setIconified(false); }
    public void minimize()   { stage.setIconified(true); }
    public boolean isShowing()    { return stage.isShowing(); }
    public boolean isIconified()  { return stage.isIconified(); }
}
