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

package io.qtrace.welcome;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The welcome of GET /api/onboarding (schema v2: slides, web/lib/onboarding.ts) read for the
 * plain fallback window, used when this QuPath has no WebView to play the HTML player.
 * Each slide with an action becomes one step (its title, its first runnable action); the
 * first slide gives the title and subtitle.
 *
 * Tolerant by design, so content newer than this module never breaks it: unknown fields are
 * ignored; an action this module cannot run becomes a link (if it has a url) or is dropped; a
 * schemaVersion above {@link #SUPPORTED_SCHEMA} shows the title only.
 */
public record WelcomeSpec(String title, String subtitle, String orgName, String logoUrl, List<Step> steps) {

    public static final int SUPPORTED_SCHEMA = 2;

    public record Step(String id, String label, String action, String url) {}

    public static WelcomeSpec parse(String json, List<String> knownActions) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return new WelcomeSpec("Welcome to qTrace", null, null, null, List.of());
        }
        int schema = o.has("schemaVersion") && o.get("schemaVersion").isJsonPrimitive()
            ? o.get("schemaVersion").getAsInt() : SUPPORTED_SCHEMA;
        List<JsonObject> slides = objects(o, "slides");
        JsonObject org = obj(o, "org");

        String title = str(o, "title");
        String subtitle = null;
        List<Step> steps = new ArrayList<>();
        if (schema <= SUPPORTED_SCHEMA) {
            if (!slides.isEmpty()) {
                title = str(slides.get(0), "title") != null ? str(slides.get(0), "title") : title;
                subtitle = str(slides.get(0), "text");
            }
            int i = 0;
            for (JsonObject s : slides) {
                String id = str(s, "id") != null ? str(s, "id") : "slide-" + i;
                i++;
                String label = str(s, "title");
                if (label == null) continue;
                for (JsonObject a : objects(s, "actions")) {
                    String action = str(a, "action"), url = str(a, "url");
                    // 'close' ends the HTML player; the fallback window has its own Close button.
                    if (action == null || action.equals("close")) continue;
                    if (knownActions.contains(action)) { steps.add(new Step(id, label, action, url)); break; }
                    if (url != null) { steps.add(new Step(id, label, "open-url", url)); break; }
                }
            }
        }
        return new WelcomeSpec(title != null ? title : "Welcome to qTrace", subtitle,
            str(org, "name"), str(org, "logo"), List.copyOf(steps));
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static List<JsonObject> objects(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray(key)) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        String s = o.get(key).getAsString();
        return s.isBlank() ? null : s;
    }
}
