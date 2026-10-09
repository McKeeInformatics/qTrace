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
 * The welcome of one or several tools, as the player plays it (docs/architecture/
 * welcome-tour.md, layer 3): for each tool, a first slide with its button and essentials, then
 * the slides written for it in the BackOffice; one closing slide at the end. Pure JSON, no
 * JavaFX: unit-tested.
 */
final class ToolContent {

    private ToolContent() {}

    /** One slide written for a tool; {@code image} null when it has no capture. */
    record Slide(String title, String text, String image) {}

    /** A tool and its welcome. {@code icon}: id of the icon of its button, drawn by the player. */
    record Item(String module, String title, String tagline, String version, String icon,
                List<String> features, List<Slide> slides) {}

    /** The content to play, or null without a tool. */
    static String of(List<Item> items) {
        if (items.isEmpty()) return null;
        JsonArray slides = new JsonArray();
        for (Item i : items) {
            slides.add(first(i));
            int n = 0;
            for (Slide s : i.slides()) slides.add(slide(i, s, ++n));
        }
        slides.add(closing());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("branch", "tools");
        root.addProperty("title", items.size() == 1 ? items.get(0).title() : "Your qTrace tools");
        root.add("slides", slides);
        return root.toString();
    }

    private static JsonObject first(Item i) {
        JsonObject s = new JsonObject();
        s.addProperty("id", "tool-" + i.module());
        s.addProperty("eyebrow", "In your panel");
        s.addProperty("title", i.title());
        s.addProperty("text", i.tagline() == null || i.tagline().isBlank()
            ? "Here is what " + i.title() + " brings."
            : i.tagline());
        s.addProperty("visual", "module");
        JsonObject m = new JsonObject();
        m.addProperty("name", i.title());
        m.addProperty("label", i.title().replaceFirst("^qTrace ", "")); // the button's caption
        m.addProperty("version", i.version());
        m.addProperty("icon", i.icon() == null || i.icon().isBlank() ? i.module() : i.icon());
        JsonArray features = new JsonArray();
        i.features().forEach(features::add);
        m.add("features", features);
        m.addProperty("news", true); // already installed: no download line
        s.add("module", m);
        s.add("actions", new JsonArray());
        return s;
    }

    private static JsonObject slide(Item i, Slide slide, int n) {
        JsonObject s = new JsonObject();
        s.addProperty("id", "tool-" + i.module() + "-" + n);
        s.addProperty("eyebrow", i.title());
        s.addProperty("title", slide.title());
        s.addProperty("text", slide.text() == null ? "" : slide.text());
        if (slide.image() != null && !slide.image().isBlank()) s.addProperty("image", slide.image());
        s.add("actions", new JsonArray());
        return s;
    }

    private static JsonObject closing() {
        JsonObject s = new JsonObject();
        s.addProperty("id", "tools-done");
        s.addProperty("eyebrow", "All set");
        s.addProperty("title", "Ready when you are");
        s.addProperty("text", "Find these again any time from Extensions > QTrace > Your tools…. "
            + "A gold square on a button of the panel means its welcome is still to be read.");
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
