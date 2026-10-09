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

import io.qtrace.Provisioner.OpenModule;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Getting started's content once the modules open to anyone are known: the trunk
 * (player/trunk.json), whose last slide offers to restart when one of them is being installed.
 * The modules themselves are not presented here: each tool's welcome plays when the tool first
 * shows in the panel, after the restart (docs/architecture/welcome-tour.md).
 * Pure JSON, no JavaFX: unit-tested.
 */
final class TrunkContent {

    private TrunkContent() {}

    /** The slide the user ends on without signing in. */
    private static final String LAST = "ready";

    static String withOpenModules(String trunkJson, List<OpenModule> modules) {
        if (modules.stream().noneMatch(OpenModule::installing)) return trunkJson;
        JsonObject root = JsonParser.parseString(trunkJson).getAsJsonObject();
        for (JsonElement el : root.getAsJsonArray("slides")) {
            JsonObject slide = el.getAsJsonObject();
            if (LAST.equals(id(slide))) restartOffer(slide, modules);
        }
        return root.toString();
    }

    /** Modules being installed load at the next start: the last slide says so and offers it. */
    private static void restartOffer(JsonObject last, List<OpenModule> modules) {
        List<OpenModule> installing = modules.stream().filter(OpenModule::installing).toList();
        String names = installing.stream().map(OpenModule::title).collect(Collectors.joining(", "));
        last.addProperty("text", last.get("text").getAsString() + " " + names
            + (installing.size() == 1 ? " starts" : " start") + " the next time you open QuPath.");
        JsonArray actions = new JsonArray();
        actions.add(action("Quit QuPath now", "quit", true));
        actions.add(action("Later", "close", false));
        last.add("actions", actions);
    }

    private static JsonObject action(String label, String action, boolean primary) {
        JsonObject a = new JsonObject();
        a.addProperty("label", label);
        a.addProperty("action", action);
        if (primary) a.addProperty("primary", true);
        return a;
    }

    private static String id(JsonObject slide) {
        return slide.has("id") ? slide.get("id").getAsString() : "";
    }
}
