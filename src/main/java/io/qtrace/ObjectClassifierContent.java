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
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what QuPath wrote for an object classifier (the JSON of
 * {@code classifiers/object_classifiers/<name>.json}) into a small, readable summary - without
 * QuPath classes, so Core, the Player and the windows all read a record the same way.
 *
 * <p>Summary shapes, all with {@code kind}:
 * <ul>
 *   <li>{@code threshold} - {@code filter, measurement, threshold, below, equals, above, classes}</li>
 *   <li>{@code composite} - {@code components[]} (each a summary, in the order QuPath applies them), {@code classes}</li>
 *   <li>{@code trained} - {@code filter, model, model_params, measurements[], classes[], probability}</li>
 *   <li>{@code unknown} - {@code type}: a classifier type this version does not read; the file itself is still kept</li>
 *   <li>{@code unreadable} - not a classifier file</li>
 * </ul>
 * Never throws: a record must still be written when a future QuPath changes the format.
 */
public final class ObjectClassifierContent {

    private ObjectClassifierContent() {}

    /** OpenCV bookkeeping of a fitted model: not a parameter anyone chose. */
    private static final Set<String> MODEL_BOOKKEEPING = Set.of("format", "is_classifier", "var_all", "var_count",
        "ord_var_count", "cat_var_count", "global_var_idx", "var_idx", "var_type", "cat_ofs", "class_labels",
        "missing_subst");

    private static final int SMALL_ARRAY = 16;

    public static JsonObject summarize(String json) {
        JsonObject root = parse(json);
        return root == null ? kind("unreadable") : summarize(root);
    }

    private static JsonObject summarize(JsonObject o) {
        String type = str(o, "object_classifier_type");
        if (type.isEmpty()) return kind("unreadable");
        switch (type) {
            case "SimpleClassifier": return simple(o);
            case "CompositeClassifier": return composite(o);
            case "OpenCVMLClassifier": return trained(o);
            default: return unknown(type);
        }
    }

    private static JsonObject simple(JsonObject o) {
        JsonObject fun = o.has("function") && o.get("function").isJsonObject() ? o.getAsJsonObject("function") : null;
        String funType = fun == null ? "" : str(fun, "classifier_fun");
        if (!"ClassifyByMeasurementFunction".equals(funType)) return unknown(funType.isEmpty() ? "SimpleClassifier" : funType);
        JsonObject s = kind("threshold");
        putFilter(s, o);
        s.addProperty("measurement", str(fun, "measurement"));
        if (fun.has("threshold") && fun.get("threshold").isJsonPrimitive()) s.add("threshold", fun.get("threshold"));
        Set<String> classes = new LinkedHashSet<>();
        for (String[] side : new String[][] {{"below", "pathClassBelow"}, {"equals", "pathClassEquals"}, {"above", "pathClassAbove"}}) {
            String name = className(fun.get(side[1]));
            if (name != null) { s.addProperty(side[0], name); classes.add(name); }
        }
        s.add("classes", array(classes));
        return s;
    }

    private static JsonObject composite(JsonObject o) {
        JsonObject s = kind("composite");
        JsonArray components = new JsonArray();
        Set<String> classes = new LinkedHashSet<>();
        if (o.has("classifiers") && o.get("classifiers").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("classifiers")) {
                JsonObject c = el.isJsonObject() ? summarize(el.getAsJsonObject()) : kind("unreadable");
                components.add(c);
                if (c.has("classes")) c.getAsJsonArray("classes").forEach(n -> classes.add(n.getAsString()));
            }
        }
        s.add("components", components);
        s.add("classes", array(classes));
        return s;
    }

    private static JsonObject trained(JsonObject o) {
        JsonObject s = kind("trained");
        putFilter(s, o);
        JsonObject classifier = o.has("classifier") && o.get("classifier").isJsonObject() ? o.getAsJsonObject("classifier") : new JsonObject();
        s.addProperty("model", str(classifier, "class"));
        s.add("model_params", modelParams(classifier));
        // QuPath wraps the extractor when features are normalized or projected (PCA): the measurements sit in the innermost one
        JsonArray measurements = new JsonArray();
        List<String> outerToInner = new ArrayList<>();
        JsonObject fe = o.has("featureExtractor") && o.get("featureExtractor").isJsonObject() ? o.getAsJsonObject("featureExtractor") : null;
        for (int depth = 0; fe != null && depth < 8; depth++) {
            if (fe.has("measurements") && fe.get("measurements").isJsonArray()) { measurements = fe.getAsJsonArray("measurements").deepCopy(); break; }
            String type = str(fe, "feature_extractor_type");
            outerToInner.add(type.contains("Normaliz") ? "normalization" : type.contains("PCA") ? "pca"
                : type.replace("FeatureExtractor", "").toLowerCase(java.util.Locale.ROOT));
            fe = fe.has("featureExtractor") && fe.get("featureExtractor").isJsonObject() ? fe.getAsJsonObject("featureExtractor") : null;
        }
        s.add("measurements", measurements);
        JsonArray preprocessing = new JsonArray();   // in the order they are applied: innermost first
        for (int i = outerToInner.size() - 1; i >= 0; i--) preprocessing.add(outerToInner.get(i));
        s.add("preprocessing", preprocessing);
        Set<String> classes = new LinkedHashSet<>();
        if (o.has("pathClasses") && o.get("pathClasses").isJsonArray())
            for (JsonElement el : o.getAsJsonArray("pathClasses")) { String n = className(el); if (n != null) classes.add(n); }
        s.add("classes", array(classes));
        if (o.has("requestProbabilityEstimate") && o.get("requestProbabilityEstimate").isJsonPrimitive())
            s.add("probability", o.get("requestProbabilityEstimate"));
        return s;
    }

    /** The choices behind a fitted OpenCV model (trees, depth, layers...), not the fitted numbers themselves. */
    private static JsonObject modelParams(JsonObject classifier) {
        JsonObject params = new JsonObject();
        if (!classifier.has("statmodel") || !classifier.get("statmodel").isJsonObject()) return params;
        for (var wrapper : classifier.getAsJsonObject("statmodel").entrySet()) {   // opencv_ml_rtrees, opencv_ml_ann_mlp...
            if (!wrapper.getValue().isJsonObject()) continue;
            for (var e : wrapper.getValue().getAsJsonObject().entrySet()) {
                if (MODEL_BOOKKEEPING.contains(e.getKey())) continue;
                JsonElement v = e.getValue();
                if (e.getKey().equals("training_params") && v.isJsonObject()) {
                    v.getAsJsonObject().entrySet().forEach(p -> { if (p.getValue().isJsonPrimitive()) params.add(p.getKey(), p.getValue()); });
                } else if (v.isJsonPrimitive()) {
                    params.add(e.getKey(), v);
                } else if (v.isJsonArray() && v.getAsJsonArray().size() <= SMALL_ARRAY && allPrimitive(v.getAsJsonArray())) {
                    params.add(e.getKey(), v);
                }
            }
        }
        return params;
    }

    private static final Pattern RUN_BY_NAME = Pattern.compile("runObjectClassifier\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    /**
     * What a list calls a recorded step: QuPath's own command name says that a classifier ran, not which one, so a
     * step that runs one by its name is titled with it ("Run object classifier: 20261105-SGI-CD14-v").
     */
    public static String stepTitle(String command, String script) {
        if (command == null || !command.equals("Run object classifier") || script == null) return command;
        Matcher m = RUN_BY_NAME.matcher(script);
        return m.find() ? command + ": " + m.group(1) : command;
    }

    /** The rule in few words, for a row or a log line; empty when there is no content (a record from before the capture). */
    public static String headline(JsonObject s) {
        if (s == null) return "";
        switch (str(s, "kind")) {
            case "threshold": return str(s, "measurement") + " >= " + number(s.get("threshold"));
            case "composite": return "composite of " + (s.has("components") ? s.getAsJsonArray("components").size() : 0);
            case "trained": {
                int n = s.has("measurements") ? s.getAsJsonArray("measurements").size() : 0;
                return str(s, "model") + " on " + n + " measurement" + (n == 1 ? "" : "s");
            }
            case "unknown": return "unknown type " + str(s, "type");
            default: return "unreadable file";
        }
    }

    /** One readable line for a window: what the classifier does. */
    public static String describe(JsonObject s) {
        if (s == null) return "unreadable classifier file";
        switch (str(s, "kind")) {
            case "threshold": return describeThreshold(s);
            case "composite": {
                StringBuilder sb = new StringBuilder();
                JsonArray components = s.has("components") ? s.getAsJsonArray("components") : new JsonArray();
                sb.append("composite of ").append(components.size()).append(": ");
                for (int i = 0; i < components.size(); i++) {
                    JsonObject c = components.get(i).getAsJsonObject();
                    sb.append(i > 0 ? ", " : "").append("threshold".equals(str(c, "kind"))
                        ? str(c, "measurement") + " >= " + number(c.get("threshold")) : describe(c));
                }
                return sb.toString();
            }
            case "trained":
                String pre = s.has("preprocessing") && s.getAsJsonArray("preprocessing").size() > 0
                    ? " (" + join(s.getAsJsonArray("preprocessing")) + ")" : "";
                return str(s, "model") + " on " + join(s.getAsJsonArray("measurements")) + pre + " -> " + join(s.getAsJsonArray("classes"));
            case "unknown": return "unknown classifier type " + str(s, "type");
            default: return "unreadable classifier file";
        }
    }

    private static String describeThreshold(JsonObject s) {
        String m = str(s, "measurement") + " >= " + number(s.get("threshold"));
        String below = str(s, "below"), equals = str(s, "equals"), above = str(s, "above");
        if (equals.equals(above)) return m + " -> " + above + ", else " + below;     // QuPath's usual "above or equal"
        return str(s, "measurement") + " < " + number(s.get("threshold")) + " -> " + below
            + ", = -> " + equals + ", > -> " + above;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JsonObject parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject kind(String kind) {
        JsonObject s = new JsonObject();
        s.addProperty("kind", kind);
        return s;
    }

    private static JsonObject unknown(String type) {
        JsonObject s = kind("unknown");
        s.addProperty("type", type);
        return s;
    }

    private static void putFilter(JsonObject s, JsonObject o) {
        if (o.has("filter") && !o.get("filter").isJsonNull()) s.addProperty("filter", o.get("filter").isJsonPrimitive()
            ? o.get("filter").getAsString() : o.get("filter").toString());
    }

    private static String className(JsonElement pathClass) {
        if (pathClass == null || !pathClass.isJsonObject()) return null;
        String n = str(pathClass.getAsJsonObject(), "name");
        return n.isEmpty() ? null : n;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static JsonArray array(Set<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    private static boolean allPrimitive(JsonArray a) {
        for (JsonElement e : a) if (!e.isJsonPrimitive()) return false;
        return true;
    }

    private static String join(JsonArray a) {
        StringBuilder sb = new StringBuilder();
        if (a != null) for (JsonElement e : a) sb.append(sb.length() > 0 ? ", " : "").append(e.getAsString());
        return sb.toString();
    }

    /** 120.0 -> "120", 200.25 -> "200.25". */
    public static String number(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return "?";
        try {
            return new BigDecimal(e.getAsString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return e.getAsString();
        }
    }
}
