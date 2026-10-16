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

/**
 * The welcome of qTrace itself — the base layer (docs/architecture/welcome-tour.md), before the
 * organization's and the tools': what a user can do on the very first start, whoever they are,
 * with or without a certificate. Today one closing slide: the bug and feature request button is the
 * panel's own, shown by the Core tool welcome. Read once
 * ({@link WelcomeExtension#CORE_REVISION}).
 * Pure JSON, no JavaFX: unit-tested.
 */
final class CoreContent {

    private CoreContent() {}

    static String of() {
        JsonArray slides = new JsonArray();
        slides.add(closing());
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("branch", "core");
        root.addProperty("title", "Welcome to qTrace");
        root.add("slides", slides);
        return root.toString();
    }

    private static JsonObject closing() {
        JsonObject s = new JsonObject();
        s.addProperty("id", "done");
        s.addProperty("eyebrow", "All set");
        s.addProperty("title", "qTrace is on board");
        s.addProperty("text", "You can start using it now: open a project and work as usual, qTrace records in the background. "
            + "Help is in Extensions > QTrace > Help.");
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
