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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Several welcomes played as one (docs/architecture/welcome-tour.md): the organization's, then
 * the tools', then what the updates bring — one window, one closing slide, one last button.
 * Each part is the content it would play alone (schema v2, loader.md § 17); only the last one
 * keeps its closing slide — with words for the whole sequence when there were several parts —
 * and its button always reads {@link #START}.
 * Pure JSON, no JavaFX: unit-tested.
 */
final class WelcomeSequence {

    /** The last button of every welcome, whatever was played before it. */
    static final String START = "Start using qTrace";

    private WelcomeSequence() {}

    /**
     * @param parts the contents in playing order; null or unreadable ones are skipped
     * @return the content to play, or null when there is nothing
     */
    static String of(String... parts) {
        List<JsonObject> roots = new ArrayList<>();
        if (parts != null) for (String p : parts) {
            JsonObject root = parse(p);
            if (root != null) roots.add(root);
        }
        if (roots.isEmpty()) return null;

        JsonArray slides = new JsonArray();
        for (int i = 0; i < roots.size(); i++) {
            boolean lastPart = i == roots.size() - 1;
            for (JsonElement el : roots.get(i).getAsJsonArray("slides")) {
                if (!lastPart && closing(el)) continue;
                slides.add(el);
            }
        }
        if (roots.size() > 1) commonWords(slides);
        startButton(slides);

        // Who it is for and whose it is come from the first part: the organization's when there is one.
        JsonObject out = roots.get(0).deepCopy();
        out.add("slides", slides);
        return out.toString();
    }

    private static JsonObject parse(String json) {
        if (json == null) return null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return root.has("slides") && root.get("slides").isJsonArray()
                && !root.getAsJsonArray("slides").isEmpty() ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A part's closing slide: "done", or "<part>-done". */
    private static boolean closing(JsonElement slide) {
        if (!slide.isJsonObject() || !slide.getAsJsonObject().has("id")) return false;
        String id = slide.getAsJsonObject().get("id").getAsString();
        return id.equals("done") || id.endsWith("-done");
    }

    /** After several welcomes the closing slide is no longer the last part's alone. */
    private static void commonWords(JsonArray slides) {
        JsonElement last = slides.get(slides.size() - 1);
        if (!closing(last)) return;
        JsonObject slide = last.getAsJsonObject();
        slide.addProperty("title", "Ready when you are");
        slide.addProperty("text", "Find all of this again any time from Extensions > QTrace: "
            + "Welcome…, Your tools… and What's new….");
    }

    /** The button that closes the last slide reads the same after every welcome. */
    private static void startButton(JsonArray slides) {
        JsonElement last = slides.get(slides.size() - 1);
        if (!last.isJsonObject() || !last.getAsJsonObject().has("actions")) return;
        for (JsonElement a : last.getAsJsonObject().getAsJsonArray("actions")) {
            if (!a.isJsonObject()) continue;
            JsonObject action = a.getAsJsonObject();
            if (action.has("action") && "close".equals(action.get("action").getAsString()))
                action.addProperty("label", START);
        }
    }
}
