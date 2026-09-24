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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Explains WHERE the current state of an image diverges from its stamp.
 *
 * The reference is the session as it was stamped — the .qtcert's qtrace_payload when the
 * Compliance chain exists, otherwise the stamped session itself. Pure JSON logic, so it is
 * shared by the Dashboard's "Why?" dialog and testable without QuPath.
 */
public final class ProvenanceDiff {

    private ProvenanceDiff() {}

    public enum Kind {
        FIELD, ANNOTATION_REMOVED, ANNOTATION_ADDED, ANNOTATION_RECREATED, ANNOTATION_MODIFIED,
        DETECTIONS, FILE, CERTIFICATE, NOT_COMPARABLE
    }

    /** One divergence. {@code stamped}/{@code current} are display strings. */
    public record Finding(Kind kind, String subject, String stamped, String current) {}

    /** Keys rewritten on every export — not part of what the validator attested. */
    private static final Set<String> IGNORED_KEYS = Set.of("exported_at");
    private static final double MOVE_TOLERANCE_PX = 1.0;
    private static final double AREA_TOLERANCE_PCT = 1.0;

    // ── .qtrace session vs stamped payload ──────────────────────────────────

    public static List<Finding> diffSession(JsonObject stamped, JsonObject current, String currentRootStatus) {
        Map<String, String> ref = flatten(withoutCertRefs(stamped));
        Map<String, String> cur = flatten(withoutCertRefs(current));
        List<Finding> out = new ArrayList<>();
        // A list element (a step, a file…) present on one side only is one finding, not one
        // per field: report it by its label and skip its fields below.
        Set<String> refElems = elements(ref.keySet()), curElems = elements(cur.keySet());
        Set<String> orphans = new TreeSet<>();
        for (String e : union(refElems, curElems)) {
            boolean inRef = refElems.contains(e), inCur = curElems.contains(e);
            if (inRef && inCur) continue;
            orphans.add(e);
            out.add(new Finding(Kind.FIELD, e, inRef ? elementLabel(ref, e) : "∅", inCur ? elementLabel(cur, e) : "∅"));
        }
        for (String key : new TreeSet<>(union(ref.keySet(), cur.keySet()))) {
            String leaf = key.substring(key.lastIndexOf('.') + 1);
            if (IGNORED_KEYS.contains(leaf)) continue;
            String elem = elementOf(key);
            if (elem != null && orphans.contains(elem)) continue;
            String a = ref.getOrDefault(key, "∅"), b = cur.getOrDefault(key, "∅");
            if (a.equals(b)) continue;
            String[] ex = excerpt(a, b);
            out.add(new Finding(Kind.FIELD, key, ex[0], ex[1]));
        }
        // The root status mirrors validation.statusLabel (Dashboard's Status column). Report it
        // only when it drifted on its own — when the stamp field changed too, that finding
        // already says it.
        String stampedStatus = ref.get("validation.statusLabel");
        boolean labelEdited = stampedStatus != null && !stampedStatus.equals(cur.get("validation.statusLabel"));
        if (!labelEdited && currentRootStatus != null && stampedStatus != null
                && !stampedStatus.equals("\"" + currentRootStatus + "\""))
            out.add(new Finding(Kind.FIELD, "status", stampedStatus, "\"" + currentRootStatus + "\""));
        return out;
    }

    /** "steps[3].params.x" → "steps[3]" (first list element in the path), null when none. */
    private static String elementOf(String key) {
        int close = key.indexOf(']');
        return close < 0 ? null : key.substring(0, close + 1);
    }

    private static Set<String> elements(Set<String> keys) {
        Set<String> out = new TreeSet<>();
        for (String k : keys) {
            String e = elementOf(k);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** A readable name for a list element: its command, name, filename or uuid. */
    private static String elementLabel(Map<String, String> flat, String elem) {
        for (String f : List.of("command", "name", "filename", "uuid")) {
            String v = flat.get(elem + "." + f);
            if (v != null && v.startsWith("\"")) return v.substring(1, v.length() - 1);
        }
        return "present";
    }

    private static final int EXCERPT_CONTEXT = 30;

    /** For long values, keeps only the differing passage with some context on each side. */
    static String[] excerpt(String a, String b) {
        if (a.length() <= 120 && b.length() <= 120) return new String[] { a, b };
        int pre = 0, max = Math.min(a.length(), b.length());
        while (pre < max && a.charAt(pre) == b.charAt(pre)) pre++;
        int suf = 0;
        while (suf < max - pre && a.charAt(a.length() - 1 - suf) == b.charAt(b.length() - 1 - suf)) suf++;
        int start = Math.max(0, pre - EXCERPT_CONTEXT);
        return new String[] { cut(a, start, a.length() - suf + EXCERPT_CONTEXT), cut(b, start, b.length() - suf + EXCERPT_CONTEXT) };
    }

    private static String cut(String s, int start, int end) {
        end = Math.min(s.length(), Math.max(end, start));
        return (start > 0 ? "…" : "") + s.substring(start, end) + (end < s.length() ? "…" : "");
    }

    /** qTrace appends the .qtcert itself to external_files after stamping — expected, not a change. */
    private static JsonObject withoutCertRefs(JsonObject session) {
        if (session == null) return new JsonObject();
        JsonObject copy = session.deepCopy();
        if (copy.has("external_files") && copy.get("external_files").isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (JsonElement e : copy.getAsJsonArray("external_files")) {
                boolean cert = e.isJsonObject() && e.getAsJsonObject().has("type")
                    && "cert".equals(e.getAsJsonObject().get("type").getAsString());
                if (!cert) kept.add(e);
            }
            copy.add("external_files", kept);
        }
        return copy;
    }

    private static Map<String, String> flatten(JsonObject o) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten(o, "", out);
        return out;
    }

    private static void flatten(JsonElement e, String prefix, Map<String, String> out) {
        if (e != null && e.isJsonObject()) {
            for (var en : e.getAsJsonObject().entrySet())
                flatten(en.getValue(), prefix.isEmpty() ? en.getKey() : prefix + "." + en.getKey(), out);
        } else if (e != null && e.isJsonArray() && !e.getAsJsonArray().isEmpty()
                && e.getAsJsonArray().get(0).isJsonObject()) {
            JsonArray arr = e.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) flatten(arr.get(i), prefix + "[" + i + "]", out);
        } else {
            out.put(prefix, String.valueOf(e));
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new TreeSet<>(a);
        s.addAll(b);
        return s;
    }

    // ── annotations ─────────────────────────────────────────────────────────

    /** Compares annotations.details arrays (uuid, class, roi_type, centroid_x/y, area_px2). */
    public static List<Finding> diffAnnotations(JsonArray stamped, JsonArray current) {
        Map<String, JsonObject> ref = byUuid(stamped), cur = byUuid(current);
        Set<String> gone = new TreeSet<>(ref.keySet()), fresh = new TreeSet<>(cur.keySet());
        gone.removeAll(cur.keySet());
        fresh.removeAll(ref.keySet());
        List<Finding> out = new ArrayList<>();

        // Same class + shape under a new UUID: recreated (typically by a Replay), maybe moved.
        for (String u : new ArrayList<>(gone)) {
            JsonObject a = ref.get(u);
            String best = null;
            double bestD = Double.MAX_VALUE;
            for (String v : fresh) {
                JsonObject b = cur.get(v);
                if (!s(a, "class").equals(s(b, "class")) || !s(a, "roi_type").equals(s(b, "roi_type"))) continue;
                double d = Math.hypot(d(b, "centroid_x") - d(a, "centroid_x"), d(b, "centroid_y") - d(a, "centroid_y"));
                if (d < bestD) { bestD = d; best = v; }
            }
            if (best == null) continue;
            gone.remove(u);
            fresh.remove(best);
            JsonObject b = cur.get(best);
            String geom = geometryChange(a, b);
            out.add(new Finding(geom == null ? Kind.ANNOTATION_RECREATED : Kind.ANNOTATION_MODIFIED,
                label(a), describe(a), shortId(best) + (geom == null ? " — same geometry, new UUID" : " — " + geom)));
        }
        for (String u : gone)  out.add(new Finding(Kind.ANNOTATION_REMOVED, label(ref.get(u)), describe(ref.get(u)), "∅"));
        for (String v : fresh) out.add(new Finding(Kind.ANNOTATION_ADDED, label(cur.get(v)), "∅", describe(cur.get(v))));

        for (String u : new TreeSet<>(ref.keySet())) {
            if (!cur.containsKey(u)) continue;
            JsonObject a = ref.get(u), b = cur.get(u);
            List<String> changes = new ArrayList<>();
            if (!s(a, "class").equals(s(b, "class"))) changes.add("class: " + s(a, "class") + " → " + s(b, "class"));
            if (!s(a, "roi_type").equals(s(b, "roi_type"))) changes.add("shape: " + s(a, "roi_type") + " → " + s(b, "roi_type"));
            String geom = geometryChange(a, b);
            if (geom != null) changes.add(geom);
            if (!changes.isEmpty())
                out.add(new Finding(Kind.ANNOTATION_MODIFIED, label(a), describe(a), String.join("; ", changes)));
        }
        return out;
    }

    private static String geometryChange(JsonObject a, JsonObject b) {
        double dx = d(b, "centroid_x") - d(a, "centroid_x"), dy = d(b, "centroid_y") - d(a, "centroid_y");
        double areaA = d(a, "area_px2"), areaB = d(b, "area_px2");
        double dArea = areaA > 0 ? (areaB - areaA) / areaA * 100 : 0;
        List<String> parts = new ArrayList<>();
        if (Math.abs(dx) > MOVE_TOLERANCE_PX || Math.abs(dy) > MOVE_TOLERANCE_PX)
            parts.add(String.format(Locale.ROOT, "moved (%+.0f,%+.0f) px", dx, dy));
        if (Math.abs(dArea) > AREA_TOLERANCE_PCT)
            parts.add(String.format(Locale.ROOT, "area %+.1f %%", dArea));
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static Map<String, JsonObject> byUuid(JsonArray arr) {
        Map<String, JsonObject> m = new HashMap<>();
        if (arr != null)
            for (JsonElement e : arr)
                if (e.isJsonObject() && e.getAsJsonObject().has("uuid"))
                    m.put(e.getAsJsonObject().get("uuid").getAsString(), e.getAsJsonObject());
        return m;
    }

    private static String label(JsonObject a) {
        return s(a, "class") + " " + s(a, "roi_type") + " (" + shortId(s(a, "uuid")) + ")";
    }

    private static String describe(JsonObject a) {
        return String.format(Locale.ROOT, "@(%.0f, %.0f), %.0f px²", d(a, "centroid_x"), d(a, "centroid_y"), d(a, "area_px2"));
    }

    private static String shortId(String uuid) {
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    private static String s(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static double d(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
    }

    // ── detections ──────────────────────────────────────────────────────────

    /** Compares session.detections blocks ({total, by_class, fingerprint_sha256}). */
    public static List<Finding> diffDetections(JsonObject stamped, JsonObject current) {
        if (current == null) return List.of();
        String curTotal = s(current, "total");
        if (stamped == null || s(stamped, "fingerprint_sha256").isEmpty())
            return List.of(new Finding(Kind.NOT_COMPARABLE, "detections",
                "not recorded in this stamp (older qTrace version)", curTotal));
        if (s(stamped, "fingerprint_sha256").equals(s(current, "fingerprint_sha256"))) return List.of();

        Map<String, Integer> a = counts(stamped), b = counts(current);
        List<String> deltas = new ArrayList<>();
        for (String cls : new TreeSet<>(union(a.keySet(), b.keySet()))) {
            int delta = b.getOrDefault(cls, 0) - a.getOrDefault(cls, 0);
            if (delta != 0) deltas.add(cls + " " + (delta > 0 ? "+" + delta : "−" + (-delta)));
        }
        String detail = deltas.isEmpty()
            ? "same counts — objects moved or reshaped"
            : String.join(", ", deltas);
        return List.of(new Finding(Kind.DETECTIONS, "detections", s(stamped, "total"), curTotal + " (" + detail + ")"));
    }

    private static Map<String, Integer> counts(JsonObject det) {
        Map<String, Integer> m = new TreeMap<>();
        if (det.has("by_class") && det.get("by_class").isJsonObject())
            for (var en : det.getAsJsonObject("by_class").entrySet()) m.put(en.getKey(), en.getValue().getAsInt());
        return m;
    }

    // ── satellite files ─────────────────────────────────────────────────────

    /**
     * Checks session.external_files ({filename, path, size_bytes, sha256}) against disk, in
     * {@code exportDir} (the .qtrace folder). A CSV log whose first size_bytes still hash to the
     * stamped value only grew since (master_validation_log.csv is append-only): not reported.
     */
    public static List<Finding> checkFiles(JsonArray externalFiles, Path exportDir) {
        List<Finding> out = new ArrayList<>();
        if (externalFiles == null) return out;
        for (JsonElement e : externalFiles) {
            if (!e.isJsonObject()) continue;
            JsonObject f = e.getAsJsonObject();
            String name = s(f, "filename"), sha = s(f, "sha256");
            if (sha.isEmpty() || "cert".equals(s(f, "type"))) continue;
            // Per-image satellites live next to the .qtrace: no fallback to the stamped absolute
            // path, which may point to another copy of the project that still has the original.
            // The validation log is shared across images and stays where it was written, so a
            // .qtrace moved without it is checked against the stamped location too.
            boolean sharedLog = "csv".equals(s(f, "type"));
            List<Path> candidates = new ArrayList<>();
            if (exportDir != null) candidates.add(exportDir.resolve(name));
            if ((exportDir == null || sharedLog) && !s(f, "path").isEmpty()) candidates.add(Path.of(s(f, "path")));
            // Only the append-only validation log may legitimately grow after the stamp.
            long size = sharedLog && f.has("size_bytes") ? f.get("size_bytes").getAsLong() : -1;
            boolean exists = false, matches = false;
            for (Path p : candidates) {
                if (!Files.exists(p)) continue;
                exists = true;
                if (sha.equals(prefixSha256(p, size))) { matches = true; break; }
            }
            if (!exists)       out.add(new Finding(Kind.FILE, name, "present", "missing"));
            else if (!matches) out.add(new Finding(Kind.FILE, name, "original file", "modified"));
        }
        return out;
    }

    /** Checks the annotations GeoJSON (written next to the trace, in the training dir). */
    public static List<Finding> checkGeoJson(String fileName, String stampedSha256, Path dir) {
        if (fileName == null || fileName.isEmpty() || stampedSha256 == null || dir == null) return List.of();
        Path p = dir.resolve(fileName);
        if (!Files.exists(p)) return List.of(new Finding(Kind.FILE, fileName, "present", "missing"));
        return stampedSha256.equals(prefixSha256(p, -1))
            ? List.of()
            : List.of(new Finding(Kind.FILE, fileName, "original file", "modified"));
    }

    /** SHA-256 of the first {@code size} bytes, or of the whole file when size < 0 or the file shrank. */
    private static String prefixSha256(Path p, long size) {
        try {
            byte[] all = Files.readAllBytes(p);
            byte[] bytes = size >= 0 && size <= all.length ? Arrays.copyOf(all, (int) size) : all;
            if (size >= 0 && size > all.length) return "";   // shrank: cannot be the stamped file
            return io.qtrace.chain.Hashing.sha256Hex(bytes);
        } catch (Exception ex) {
            return "";
        }
    }
}
