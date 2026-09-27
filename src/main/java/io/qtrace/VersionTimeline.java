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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model of the Version window's step timeline — pure JSON → rows, no JavaFX.
 *
 * A .qtrace is read session by session: each captured step becomes a {@link Kind#STEP} row,
 * and each session closes with one milestone row — {@link Kind#STAMP} when it was validated,
 * {@link Kind#UNSTAMPED} when it was only autosaved. Work QuPath never logs as a workflow step
 * but qTrace captures on the side — pixel classifier training, Warpy alignment — is inserted
 * as a step at its own time ({@link Source}), once, in the first session that carries it (those
 * session blocks are cumulative).
 *
 * The short grey line under a step ({@link #summarize}) reads functionally rather than
 * technically: what a model detects and on which channels, which classifier produced what,
 * the stain vectors. Everything else falls back to the command's argument values. Local
 * paths are always reduced to their last element so a screenshot never shows a disk layout.
 */
public final class VersionTimeline {

    public enum Kind { STEP, STAMP, UNSTAMPED }

    /** Where a step row comes from: the QuPath workflow, or a record qTrace captured on the side. */
    public enum Source { WORKFLOW, PIXEL_TRAINING, ALIGNMENT }

    /**
     * One timeline row. {@code sessionIndex} links it to the commit graph node; step-only
     * fields ({@code command}, {@code summary}, {@code script}, flags) are empty on milestones.
     * {@code details} holds a captured record's facts, keyed by i18n key (empty for workflow steps).
     */
    public record Entry(Kind kind, Source source, int sessionIndex, String time, String timestampIso,
                        String command, String summary, String script, Map<String, String> details,
                        boolean scriptable, boolean preTracking, boolean replayExcluded, boolean deleted) {}

    static final int MAX_SUMMARY = 60;
    private static final int MAX_ARG = 40;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    // modelPath("…") or, in replay scripts, modelPath(_qtraceInstanSegPath("name", "…/name-0.1.1"))
    private static final Pattern INSTANSEG_MODEL  = Pattern.compile("\\.model(?:Path|Name)\\(([^\\n]*)");
    private static final Pattern QUOTED           = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern MODEL_VERSION    = Pattern.compile("^(.*?)-(\\d+(?:\\.\\d+)+)$");
    private static final Pattern CHANNEL          = Pattern.compile(
        "createChannelExtractor\\(\\s*(?:[\"']([^\"']+)[\"']|(\\d+))\\s*\\)");
    // Imaging-mass-cytometry channel names: "Ir(193)_193Ir_DNA-2" → "DNA-2"
    private static final Pattern METAL_CHANNEL    = Pattern.compile("^[A-Za-z]+\\(\\d+\\)_\\d+[A-Za-z]+_(.+)$");
    private static final Pattern CLASSIFIER_CALL  = Pattern.compile(
        "(addPixelClassifierMeasurements|createAnnotationsFromPixelClassifier"
        + "|createDetectionsFromPixelClassifier|classifyDetectionsByCentroid"
        + "|runObjectClassifier|loadPixelClassifier|loadObjectClassifier)\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern STAIN_NAME       = Pattern.compile("\\\\?\"Stain (\\d)\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]+)");
    private static final Pattern STAIN_VALUES     = Pattern.compile("\\\\?\"Values (\\d)\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]+)");

    private VersionTimeline() {}

    // ── Rows ──────────────────────────────────────────────────────────────────

    public static List<Entry> build(JsonObject root, ZoneId zone) {
        List<Entry> out = new ArrayList<>();
        if (root == null || !root.has("sessions") || !root.get("sessions").isJsonArray()) return out;
        JsonArray sessions = root.getAsJsonArray("sessions");
        Set<String> seenRecords = new HashSet<>();
        for (int i = 0; i < sessions.size(); i++) {
            if (!sessions.get(i).isJsonObject()) continue;
            JsonObject s = sessions.get(i).getAsJsonObject();

            List<Entry> rows = new ArrayList<>();
            if (s.has("steps") && s.get("steps").isJsonArray()) {
                for (JsonElement el : s.getAsJsonArray("steps")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject st = el.getAsJsonObject();
                    String command = str(st, "command", "");
                    String script  = str(st, "script_fragment", null);
                    String ts      = str(st, "timestamp", "");
                    rows.add(new Entry(Kind.STEP, Source.WORKFLOW, i, time(ts, zone), ts, command,
                        summarize(command, script), script, Map.of(),
                        bool(st, "is_scriptable"), bool(st, "pre_tracking"),
                        bool(st, "replay_excluded"), bool(st, "deleted")));
                }
            }
            for (Entry rec : capturedRecords(s, i, zone, seenRecords)) insertByTime(rows, rec);
            out.addAll(rows);

            boolean stamped = StampIntegrity.isStamped(s);
            String ts = str(s, "exported_at", "");
            if (stamped && s.get("validation").isJsonObject()) {
                String vts = str(s.getAsJsonObject("validation"), "timestamp", null);
                if (vts != null) ts = vts;
            }
            out.add(new Entry(stamped ? Kind.STAMP : Kind.UNSTAMPED, null, i, time(ts, zone), ts,
                "", "", null, Map.of(), false, false, false, false));
        }
        return out;
    }

    /** Pixel classifier trainings and applied alignments of a session not already shown earlier. */
    private static List<Entry> capturedRecords(JsonObject s, int index, ZoneId zone, Set<String> seen) {
        List<Entry> out = new ArrayList<>();
        if (s.has("pixel_classifiers") && s.get("pixel_classifiers").isJsonArray()) {
            for (JsonElement el : s.getAsJsonArray("pixel_classifiers")) {
                if (!el.isJsonObject()) continue;
                JsonObject c = el.getAsJsonObject();
                String name = str(c, "name", "");
                String ts   = str(c, "saved_at", "");
                if (!seen.add("pc|" + name + "|" + ts)) continue;

                List<String> classes = strings(c, "classes");
                List<String> parts = new ArrayList<>();
                if (!name.isEmpty()) parts.add(name);
                if (!classes.isEmpty()) parts.add(classes.size() + " classes");
                String res = number(c, "resolution_um");
                if (res != null) parts.add(res + " µm/px");
                String type = str(c, "classifier_type", null);
                if (type != null && !type.isBlank()) parts.add(type);

                Map<String, String> d = new LinkedHashMap<>();
                putIf(d, "graph.rec.name", name);
                putIf(d, "graph.rec.classes", String.join(", ", classes));
                putIf(d, "graph.rec.channels", number(c, "n_channels"));
                putIf(d, "graph.rec.features", String.join(", ", strings(c, "features")));
                putIf(d, "graph.rec.scales", String.join(", ", strings(c, "scales")));
                putIf(d, "graph.rec.resolution", res == null ? null : res + " µm/px");
                putIf(d, "graph.rec.type", type);
                putIf(d, "graph.rec.regions", number(c, "n_training_regions"));
                putIf(d, "graph.rec.fidelity", str(c, "fidelity", null));
                out.add(new Entry(Kind.STEP, Source.PIXEL_TRAINING, index, time(ts, zone), ts,
                    "Train pixel classifier", truncate(String.join(" · ", parts), MAX_SUMMARY), null, d,
                    false, false, false, false));
            }
        }
        if (s.has("alignment") && s.get("alignment").isJsonObject()) {
            JsonObject a = s.getAsJsonObject("alignment");
            String ts = str(a, "detected_at", "");
            if (bool(a, "applied") && seen.add("al|" + ts)) {
                String source = str(a, "capture_source", "");
                String moving = str(a, "moving_image_name", null);
                String ref    = str(a, "reference_image_name", null);
                String type   = str(a, "transform_type", null);
                List<String> parts = new ArrayList<>();
                if (moving != null || ref != null)
                    parts.add((moving == null ? "?" : moving) + " → " + (ref == null ? "?" : ref));
                if (type != null && !type.isBlank()) parts.add(type);

                Map<String, String> d = new LinkedHashMap<>();
                putIf(d, "graph.rec.moving", moving);
                putIf(d, "graph.rec.reference", ref);
                putIf(d, "graph.rec.transform", type);
                String sx = number(a, "scale_x"), sy = number(a, "scale_y");
                if (sx != null) d.put("graph.rec.scale", sx + (sy != null && !sy.equals(sx) ? " × " + sy : ""));
                String tx = number(a, "translate_x"), ty = number(a, "translate_y");
                if (tx != null && ty != null) d.put("graph.rec.translation", "(" + tx + ", " + ty + ") px");
                putIf(d, "graph.rec.capture", source);
                out.add(new Entry(Kind.STEP, Source.ALIGNMENT, index, time(ts, zone), ts,
                    source.startsWith("Warpy") ? "Align image (Warpy)" : "Align image",
                    truncate(String.join(" · ", parts), MAX_SUMMARY), null, d,
                    false, false, false, false));
            }
        }
        return out;
    }

    /** Before the first step that happened later; steps keep their recorded order. */
    private static void insertByTime(List<Entry> rows, Entry rec) {
        Instant at = instant(rec.timestampIso());
        if (at != null) {
            for (int k = 0; k < rows.size(); k++) {
                Instant t = instant(rows.get(k).timestampIso());
                if (t != null && t.isAfter(at)) { rows.add(k, rec); return; }
            }
        }
        rows.add(rec);
    }

    public static int stepCount(List<Entry> entries) {
        int n = 0;
        for (Entry e : entries) if (e.kind() == Kind.STEP) n++;
        return n;
    }

    /** {@code 9c41…e07a} — the image hash of the latest stamp, or "" when none was stamped. */
    public static String shortHash(JsonObject root) {
        if (root == null || !root.has("sessions") || !root.get("sessions").isJsonArray()) return "";
        JsonArray sessions = root.getAsJsonArray("sessions");
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (!sessions.get(i).isJsonObject()) continue;
            JsonObject s = sessions.get(i).getAsJsonObject();
            if (!s.has("validation") || !s.get("validation").isJsonObject()) continue;
            String h = str(s.getAsJsonObject("validation"), "imageHash", null);
            if (h != null && h.length() >= 8) return h.substring(0, 4) + "…" + h.substring(h.length() - 4);
        }
        return "";
    }

    // ── Summary line ──────────────────────────────────────────────────────────

    /** Short parameter line shown under a step's command ("" when nothing useful). */
    public static String summarize(String command, String script) {
        if (script == null || script.isBlank()) return "";

        if (script.contains("InstanSeg")) {
            String s = instanSeg(script);
            if (s != null) return truncate(s, MAX_SUMMARY);
        }

        if (script.contains("setColorDeconvolutionStains")) {
            String s = stains(script);
            if (s != null) return truncate(s, MAX_SUMMARY);
        }

        Matcher c = CLASSIFIER_CALL.matcher(script);
        if (c.find()) {
            String name = lastElement(c.group(2));
            if (name.toLowerCase().endsWith(".json")) name = name.substring(0, name.length() - 5);
            String produces = switch (c.group(1)) {
                case "createAnnotationsFromPixelClassifier" -> " → annotations";
                case "createDetectionsFromPixelClassifier"  -> " → detections";
                case "addPixelClassifierMeasurements"       -> " → measurements";
                case "classifyDetectionsByCentroid", "runObjectClassifier" -> " → classifies detections";
                default -> "";
            };
            return truncate(name + produces, MAX_SUMMARY);
        }

        return truncate(String.join(" · ", genericArgs(script)), MAX_SUMMARY);
    }

    /** "nuclei + cells · fluorescence 0.1.1 · DNA-2, ICSK2" — what, which model, on which channels. */
    private static String instanSeg(String script) {
        Matcher m = INSTANSEG_MODEL.matcher(script);
        if (!m.find()) return null;
        String model = null;
        Matcher q = QUOTED.matcher(m.group(1));
        while (q.find()) model = q.group(1);      // last literal = the path, which carries the version
        if (model == null) return null;
        model = lastElement(model);

        String name = model, version = "";
        Matcher v = MODEL_VERSION.matcher(model);
        if (v.matches()) { name = v.group(1); version = v.group(2); }
        String modality = "";
        for (String mod : List.of("fluorescence_", "brightfield_")) {
            if (name.startsWith(mod)) { modality = mod.substring(0, mod.length() - 1); name = name.substring(mod.length()); }
        }
        String what = name.replace("_and_", " + ").replace('_', ' ').strip();

        List<String> parts = new ArrayList<>();
        if (!what.isEmpty()) parts.add(what);
        String which = (modality + " " + version).strip();
        if (!which.isEmpty()) parts.add(which);

        List<String> channels = new ArrayList<>();
        Matcher ch = CHANNEL.matcher(script);
        while (ch.find()) {
            if (ch.group(1) != null) {
                Matcher metal = METAL_CHANNEL.matcher(ch.group(1));
                channels.add(metal.matches() ? metal.group(1) : ch.group(1));
            } else {
                channels.add("ch " + ch.group(2));
            }
        }
        if (!channels.isEmpty()) {
            String list = String.join(", ", channels.subList(0, Math.min(3, channels.size())));
            if (channels.size() > 3) list += " +" + (channels.size() - 3);
            parts.add(list);
        }
        return String.join(" · ", parts);
    }

    /** "H 0.651 0.701 0.290 · DAB 0.269 0.568 0.778" — every stain, background left out. */
    private static String stains(String script) {
        Map<String, String> names = new LinkedHashMap<>(), values = new LinkedHashMap<>();
        Matcher n = STAIN_NAME.matcher(script);
        while (n.find()) names.put(n.group(1), n.group(2).strip());
        Matcher v = STAIN_VALUES.matcher(script);
        while (v.find()) values.put(v.group(1), v.group(2).strip());
        if (names.isEmpty()) return null;

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            String label = switch (e.getValue().toLowerCase(Locale.ROOT)) {
                case "hematoxylin", "haematoxylin" -> "H";
                case "eosin" -> "E";
                default -> e.getValue();
            };
            StringBuilder sb = new StringBuilder(label);
            String vals = values.get(e.getKey());
            if (vals != null) {
                for (String x : vals.split("\\s+")) {
                    try { sb.append(' ').append(String.format(Locale.ROOT, "%.3f", Double.parseDouble(x))); }
                    catch (NumberFormatException ignored) { sb.append(' ').append(x); }
                }
            }
            parts.add(sb.toString());
        }
        return String.join(" · ", parts);
    }

    /** Values of the first call's top-level arguments, quotes stripped, noise dropped. */
    private static List<String> genericArgs(String script) {
        List<String> out = new ArrayList<>();
        int open = script.indexOf('(');
        if (open < 0) return out;

        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = open + 1; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (quote != 0) {
                if (ch == quote && script.charAt(i - 1) != '\\') quote = 0;
                cur.append(ch);
                continue;
            }
            if (ch == '"' || ch == '\'') { quote = ch; cur.append(ch); continue; }
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            if (ch == ')' || ch == ']' || ch == '}') {
                if (depth == 0) { addArg(out, cur.toString()); return out; }
                depth--;
            }
            if (ch == ',' && depth == 0) { addArg(out, cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        return out;   // unbalanced — keep what was read, drop the unterminated tail
    }

    private static void addArg(List<String> out, String raw) {
        String a = raw.strip();
        if (a.length() >= 2 && (a.charAt(0) == '"' || a.charAt(0) == '\'') && a.charAt(a.length() - 1) == a.charAt(0))
            a = a.substring(1, a.length() - 1).strip();
        if (a.isEmpty() || a.startsWith("{") || a.startsWith("[")) return;   // JSON / lists: noise
        if (a.contains("/") || a.contains("\\")) a = lastElement(a);
        else if (a.matches("[a-z][\\w]*(\\.[a-z][\\w]*)+\\.[A-Z]\\w*")) a = a.substring(a.lastIndexOf('.') + 1);
        out.add(truncate(a, MAX_ARG));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String lastElement(String path) {
        String p = path.replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Instant instant(String iso) {
        try { return iso == null || iso.isBlank() ? null : Instant.parse(iso); }
        catch (Exception e) { return null; }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray())
            for (JsonElement e : o.getAsJsonArray(key))
                if (!e.isJsonNull()) out.add(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                    ? plain(e.getAsBigDecimal()) : e.getAsString());
        return out;
    }

    /** 8.0 → "8", 120.40 → "120.4"; null when absent or not a number. */
    private static String number(JsonObject o, String key) {
        try {
            return (o.has(key) && !o.get(key).isJsonNull()) ? plain(o.get(key).getAsBigDecimal()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String plain(BigDecimal b) {
        return b.stripTrailingZeros().toPlainString();
    }

    private static void putIf(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }

    private static String time(String iso, ZoneId zone) {
        if (iso == null || iso.isBlank()) return "";
        try {
            return HMS.format(Instant.parse(iso).atZone(zone));
        } catch (Exception e) {
            return iso.length() >= 19 ? iso.substring(11, 19) : iso;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : def;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }
}
