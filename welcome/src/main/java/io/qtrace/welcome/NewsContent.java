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
import com.google.gson.JsonObject;

import java.util.List;

/**
 * What the Welcome plays after qTrace modules were updated: one slide per module whose new
 * version has something to say (its button, and what that version brings — written in the
 * BackOffice), then a closing slide. Same slide shape as the welcome itself (player.js). Pure
 * JSON, no JavaFX: unit-tested.
 */
final class NewsContent {

    private NewsContent() {}

    /**
     * A module and what its version brings.
     *
     * @param icon     id of the icon of its button, drawn by the player
     * @param whatsNew what the version brings, three points at most; empty: nothing to present
     */
    record Item(String module, String title, String version, String icon, List<String> whatsNew) {}

    /** The content to play, or null when no item has anything to say. */
    static String of(List<Item> items) {
        JsonArray slides = new JsonArray();
        for (Item i : items) {
            if (i.whatsNew() == null || i.whatsNew().isEmpty()) continue;
            slides.add(slide(i));
        }
        if (slides.isEmpty()) return null;
        slides.add(closing());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("branch", "news");
        root.addProperty("title", "What's new in qTrace");
        root.add("slides", slides);
        return root.toString();
    }

    private static JsonObject slide(Item i) {
        JsonObject s = new JsonObject();
        s.addProperty("id", "news-" + i.module());
        s.addProperty("eyebrow", "Updated to " + i.version());
        s.addProperty("title", i.title());
        s.addProperty("text", "What version " + i.version() + " brings.");
        s.addProperty("visual", "module");
        JsonObject m = new JsonObject();
        m.addProperty("name", i.title());
        m.addProperty("label", i.title().replaceFirst("^qTrace ", "")); // the button's caption
        m.addProperty("version", i.version());
        m.addProperty("icon", i.icon() == null || i.icon().isBlank() ? i.module() : i.icon());
        JsonArray points = new JsonArray();
        i.whatsNew().forEach(points::add);
        m.add("features", points);
        m.addProperty("news", true);
        s.add("module", m);
        s.add("actions", new JsonArray());
        return s;
    }

    private static JsonObject closing() {
        JsonObject s = new JsonObject();
        s.addProperty("id", "news-done");
        s.addProperty("eyebrow", "All set");
        s.addProperty("title", "You are up to date");
        s.addProperty("text", "Find this again any time from Extensions > QTrace > What's new…, "
            + "and choose in Settings whether it shows by itself after an update.");
        s.addProperty("visual", "done");
        JsonArray actions = new JsonArray();
        JsonObject close = new JsonObject();
        close.addProperty("label", WelcomeSequence.START);
        close.addProperty("action", "close");
        close.addProperty("primary", true);
        actions.add(close);
        s.add("actions", actions);
        return s;
    }
}
