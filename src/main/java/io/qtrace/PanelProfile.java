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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The panel an organization chose for its members: the ordered list of the tools it shows
 * (BackOffice › an organization › Panel). It comes with the licence's modules (GET /api/modules,
 * field {@code panel}) and the last one is kept in {@code ~/.qTrace/panel-profile.json} for
 * offline starts. Without a profile the panel is qTrace's own, unchanged.
 *
 * <p>A tool is one of Core's buttons ({@link #STAMP}…) or a module, by its Qtrace-Module name
 * ("player", "library", "training"…). The panel and the mini-panel show the profile's tools in
 * its order, and the QTrace menus keep the entries of those tools only. Settings and the bug
 * report are always there, and the capture goes on whatever is shown. This is what is
 * displayed, not a lock: a tool left out is still installed.
 */
public final class PanelProfile {

    private static final Logger log = LoggerFactory.getLogger(PanelProfile.class);
    private static final String TAG = "[qtrace-panel] ";

    /** Core's own panel buttons. */
    public static final String STAMP = "stamp", UPLOAD = "upload", REPORT = "report",
                               DASHBOARD = "dashboard", IMPORT = "import", RESET = "reset";
    /** The buttons Core draws for the windows a module brings. */
    public static final String PLAYER = "player", VERSIONS = "versiongraph";

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".qTrace", "panel-profile.json");

    private static volatile List<String> tools;      // null = no profile
    private static volatile boolean loaded;
    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private PanelProfile() {}

    // ── Rule (pure) ──────────────────────────────────────────────────────────────

    /** The profile of an /api/modules descriptor; null when it carries none. */
    static List<String> parse(String descriptorJson) {
        try {
            JsonObject o = JsonParser.parseString(descriptorJson).getAsJsonObject();
            return o.has("panel") && o.get("panel").isJsonObject() ? toolsOf(o.getAsJsonObject("panel")) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> toolsOf(JsonObject panel) {
        if (!panel.has("tools") || !panel.get("tools").isJsonArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement el : panel.getAsJsonArray("tools")) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) continue;
            String tool = el.getAsString().trim().toLowerCase(Locale.ROOT);
            if (!tool.isEmpty() && !out.contains(tool)) out.add(tool);
        }
        return List.copyOf(out);
    }

    static boolean shows(String tool, List<String> profile) {
        return profile == null || profile.contains(tool);
    }

    // ── Last profile, kept on disk ───────────────────────────────────────────────

    static List<String> read(Path file) {
        try {
            return toolsOf(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
        } catch (Exception e) {
            return null;
        }
    }

    static void write(Path file, List<String> profile) {
        try {
            if (profile == null) {
                Files.deleteIfExists(file);
                return;
            }
            JsonArray arr = new JsonArray();
            profile.forEach(arr::add);
            JsonObject o = new JsonObject();
            o.add("tools", arr);
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString());
        } catch (Exception e) {
            log.info(TAG + "could not save {}: {}", file.getFileName(), e.toString());
        }
    }

    // ── Live state ───────────────────────────────────────────────────────────────

    private static void load() {
        if (loaded) return;
        synchronized (PanelProfile.class) {
            if (loaded) return;
            tools = read(FILE);
            if (tools != null) log.info(TAG + "organization panel: {}", tools);
            loaded = true;
        }
    }

    /** The organization's tools, in its order; null when there is no profile. */
    public static List<String> tools() {
        load();
        return tools;
    }

    /** False when the organization's panel leaves this tool out. */
    public static boolean shows(String tool) {
        return shows(tool, tools());
    }

    /** True when a module's own menu entry is to be shown: served, and in the organization's panel. */
    public static boolean menuShows(String module) {
        return ModuleEntitlements.isEntitled(module) && shows(module);
    }

    /**
     * The server's answer: the profile of the licence's descriptor, or none — also without a
     * certificate, which belongs to no organization.
     */
    static void accept(String descriptorJson) {
        load();
        apply(descriptorJson == null ? null : parse(descriptorJson), FILE);
    }

    /** @param file where to keep it; null keeps nothing (tests) */
    static void apply(List<String> profile, Path file) {
        if (Objects.equals(profile, tools)) return;
        tools = profile;
        loaded = true;
        log.info(TAG + "organization panel: {}", profile == null ? "none" : profile);
        if (file != null) write(file, profile);
        for (Runnable l : LISTENERS.values()) l.run();
    }

    /**
     * Called (on the thread that received it) each time the profile changes — the server's
     * answer comes after the panel was drawn. One listener per {@code owner}.
     */
    public static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    static void setForTest(List<String> profile) {
        tools = profile;
        loaded = true;
        LISTENERS.clear();
    }
}
