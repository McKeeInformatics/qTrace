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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The objects a command ran on, when QuPath does not write them down.
 *
 * <p>Many QuPath commands run on the current selection: the pixel classifier's Create objects,
 * Add measurements, every {@code runPlugin} (cell detection…), InstanSeg… When the user picks
 * "All annotations" or "Full image", QuPath records {@code selectAnnotations()} or
 * {@code resetSelection()} before the command; when the user keeps "Current selection" — the
 * rectangle just drawn or clicked — QuPath records nothing, and the command, replayed, runs on
 * whatever is selected then: nothing, so the whole image.
 *
 * <p>So the fragment the record keeps for such a command first selects the same objects again —
 * by ID (a replayed manual annotation keeps its own), else by geometry (an object a replayed
 * command made again) — and stops the replay with a clear error rather than run on others.
 * The fragment carries it, so every reader of the record (MetaScript, the Player, the CLI, a
 * replay's own trace) runs it alike.
 */
public final class SelectionScope {

    /** First line of a scoped fragment; a fragment that has it is never scoped again. */
    public static final String MARKER = "// qTrace: QuPath ran this on the objects selected at the time - the same ones are selected first";

    /** One object of the selection: its ID, ROI type, name and class (both may be null), geometry as WKT (null: by ID only). */
    public record Target(String id, String roiType, String name, String pathClass, String wkt) {}

    /** Commands that run on the current selection. Deleting it is not one: a deleted manual annotation is already out of the replay. */
    private static final Pattern DEPENDS = Pattern.compile(
        "\\b(createAnnotationsFromPixelClassifier|createDetectionsFromPixelClassifier|addPixelClassifierMeasurements"
        + "|runPlugin|addShapeMeasurements|mergeSelectedAnnotations|duplicateSelectedAnnotations|makeInverseAnnotation"
        + "|getSelectedObjects?)\\s*\\("
        + "|\\binstanseg\\b[\\s\\S]*\\.detectObjects\\s*\\(\\s*\\)",
        Pattern.CASE_INSENSITIVE);

    /** Commands that set the selection themselves: what follows them replays as it ran. */
    private static final Pattern SETS = Pattern.compile(
        "^\\s*(resetSelection|selectAnnotations|selectDetections|selectCells|selectTiles|selectTMACores"
        + "|selectObjectsByClassification|selectObjectsByClass|selectObjects|selectAllObjects)\\s*\\(");

    private SelectionScope() {}

    /** Whether QuPath runs this fragment on the current selection. */
    public static boolean dependsOnSelection(String script) {
        return script != null && DEPENDS.matcher(script).find();
    }

    /** Whether this fragment sets the selection (so the next command's selection is in the record already). */
    public static boolean setsSelection(String script) {
        return script != null && SETS.matcher(script).find();
    }

    /** Whether this fragment already selects its objects first. */
    public static boolean isScoped(String script) {
        return script != null && script.startsWith(MARKER);
    }

    /** The fragment, preceded by what selects {@code targets} again; QuPath's command comes last, unchanged. */
    public static String scoped(String script, List<Target> targets) {
        if (isScoped(script) || targets == null || targets.isEmpty()) return script;
        StringBuilder key = new StringBuilder(script);
        for (Target t : targets) key.append('\n').append(t.id());
        String v = "_qtsel_" + hash8(key.toString());

        StringBuilder sb = new StringBuilder(MARKER).append('\n');
        sb.append("def ").append(v).append(" = [\n");
        for (Target t : targets) {
            sb.append("    ['id': ").append(quote(t.id()))
              .append(", 'label': ").append(quote(label(t)))
              .append(", 'wkt': ").append(quote(t.wkt())).append("],\n");
        }
        sb.append("]\n");
        sb.append("def ").append(v).append("_all = getCurrentHierarchy().getFlattenedObjectList(null).findAll { !it.isRootObject() }\n");
        sb.append("def ").append(v).append("_found = ").append(v).append(".collect { t ->\n");
        sb.append("    def o = ").append(v).append("_all.find { it.getID().toString() == t.id }\n");
        sb.append("    if (o == null && t.wkt != null) {\n");
        sb.append("        def g = new org.locationtech.jts.io.WKTReader().read(t.wkt)\n");
        sb.append("        o = ").append(v).append("_all.find { it.hasROI() && it.getROI().getGeometry().getEnvelopeInternal().equals(g.getEnvelopeInternal()) && it.getROI().getGeometry().equalsTopo(g) }\n");
        sb.append("    }\n");
        sb.append("    if (o == null) throw new IllegalStateException('qTrace: the ' + t.label + ' this step ran on is not on this image - it is not run on other objects')\n");
        sb.append("    o\n");
        sb.append("}\n");
        sb.append("getCurrentHierarchy().getSelectionModel().setSelectedObjects(").append(v).append("_found, null)\n");
        sb.append(script);
        return sb.toString();
    }

    private static final Pattern TARGET_LINE = Pattern.compile(
        "^    \\['id': ('(?:[^'\\\\]|\\\\.)*'|null), 'label': ('(?:[^'\\\\]|\\\\.)*'|null), 'wkt': ('(?:[^'\\\\]|\\\\.)*'|null)\\],$",
        Pattern.MULTILINE);

    /** The objects a scoped fragment selects first (none for another fragment) — a replayed instruction names them again. */
    public static List<Target> targetsOf(String script) {
        if (!isScoped(script)) return List.of();
        int end = script.indexOf("\n]\n");
        String head = end < 0 ? script : script.substring(0, end + 1);
        List<Target> out = new java.util.ArrayList<>();
        var m = TARGET_LINE.matcher(head);
        while (m.find()) {
            String label = unquote(m.group(2));
            String type = label, name = null;
            int q = label == null ? -1 : label.indexOf(" \"");
            if (q > 0 && label.endsWith("\"")) { type = label.substring(0, q); name = label.substring(q + 2, label.length() - 1); }
            out.add(new Target(unquote(m.group(1)), type, name, null, unquote(m.group(3))));
        }
        return out;
    }

    private static String unquote(String q) {
        if (q == null || q.equals("null")) return null;
        String s = q.substring(1, q.length() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    /** What the step JSON keeps of the selection, for the views (the geometry is in the script only). */
    public static JsonArray toJson(List<Target> targets) {
        JsonArray a = new JsonArray();
        for (Target t : targets) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.id());
            o.addProperty("roi_type", t.roiType());
            if (t.name() != null) o.addProperty("name", t.name());
            if (t.pathClass() != null) o.addProperty("class", t.pathClass());
            a.add(o);
        }
        return a;
    }

    private static String label(Target t) {
        String type = t.roiType() == null ? "object" : t.roiType();
        return t.name() == null || t.name().isBlank() ? type : type + " \"" + t.name() + "\"";
    }

    /** A Groovy single-quoted string, or null. */
    private static String quote(String s) {
        if (s == null) return "null";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    private static String hash8(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
