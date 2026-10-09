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
import java.util.Set;
import java.util.TreeSet;

/**
 * Which installed modules the licence still includes. The server decides (GET /api/modules
 * lists the modules served to this certificate, docs/architecture/loader.md § 6 and § 15); a
 * module that is installed but no longer served is switched off: Core ignores what it plugged
 * in (e.g. {@link StampSections}) and About shows it greyed out.
 *
 * <p>The last answer is kept in {@code ~/.qTrace/modules-entitled.json} for offline starts.
 * Without any answer yet nothing is taken away. This is an honest display state, not a lock:
 * what a module is worth must be enforced by the server.
 */
public final class ModuleEntitlements {

    private static final Logger log = LoggerFactory.getLogger(ModuleEntitlements.class);
    private static final String TAG = "[qtrace-modules] ";

    /** Public modules (release bootstrap): never served by /api/modules, never taken away. */
    private static final Set<String> FREE = Set.of("core", "provisioning", "welcome", "loader");

    private static final Path DIR      = Path.of(System.getProperty("user.home"), ".qTrace");
    private static final Path FILE     = DIR.resolve("modules-entitled.json");
    /** Developer override, one module name per line: keeps a locally built module switched on. */
    private static final Path OVERRIDE = DIR.resolve("dev-modules");

    private static volatile Set<String> served;      // null = no answer known
    private static volatile Set<String> override;
    private static volatile boolean loaded;
    private static final java.util.Map<Object, Runnable> LISTENERS = new java.util.concurrent.ConcurrentHashMap<>();

    private ModuleEntitlements() {}

    // ── Rule (pure) ──────────────────────────────────────────────────────────────

    /** Module names of an /api/modules descriptor; null when it is not one (error, HTML…). */
    static Set<String> names(String descriptorJson) {
        try {
            JsonObject o = JsonParser.parseString(descriptorJson).getAsJsonObject();
            if (!o.has("files")) return null;
            Set<String> out = new TreeSet<>();
            for (JsonElement el : o.getAsJsonArray("files")) {
                JsonObject f = el.getAsJsonObject();
                if (f.has("module") && !f.get("module").isJsonNull()) out.add(f.get("module").getAsString());
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * What this workstation is served: the modules open to anyone (/api/modules/open) plus the
     * ones its licence includes (/api/modules). Null — nothing known, the last answer stands —
     * when the open list did not come, or the licence's did not while there is a licence.
     */
    static Set<String> combine(Set<String> open, Set<String> licensed, boolean hasLicence) {
        if (open == null || (hasLicence && licensed == null)) return null;
        Set<String> out = new TreeSet<>(open);
        if (licensed != null) out.addAll(licensed);
        return out;
    }

    static boolean entitled(String module, Set<String> served, Set<String> override) {
        return FREE.contains(module) || served == null || served.contains(module) || override.contains(module);
    }

    // ── Last answer, kept on disk ────────────────────────────────────────────────

    static Set<String> read(Path file) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Set<String> out = new TreeSet<>();
            for (JsonElement el : o.getAsJsonArray("modules")) out.add(el.getAsString());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    static void write(Path file, Set<String> modules) {
        try {
            JsonArray arr = new JsonArray();
            new TreeSet<>(modules).forEach(arr::add);
            JsonObject o = new JsonObject();
            o.add("modules", arr);
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString());
        } catch (Exception e) {
            log.info(TAG + "could not save {}: {}", file.getFileName(), e.toString());
        }
    }

    static Set<String> readOverride(Path file) {
        Set<String> out = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(file)) {
                String m = line.trim();
                if (!m.isEmpty() && !m.startsWith("#")) out.add(m);
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ── Live state ───────────────────────────────────────────────────────────────

    private static void load() {
        if (loaded) return;
        synchronized (ModuleEntitlements.class) {
            if (loaded) return;
            served = read(FILE);
            override = readOverride(OVERRIDE);
            if (!override.isEmpty()) log.info(TAG + "developer override, kept on: {}", override);
            loaded = true;
        }
    }

    /** False when the module is installed but the licence no longer includes it. */
    public static boolean isEntitled(String module) {
        load();
        return entitled(module, served, override);
    }

    /**
     * What this workstation is served, from the two descriptors the provisioning module
     * fetched: /api/modules/open and, with a certificate, /api/modules. Null — nothing known,
     * the last answer stands — when one that was expected did not come or is not a descriptor.
     */
    static Set<String> answer(String openDescriptor, String licensedDescriptor, boolean hasLicence) {
        return combine(names(openDescriptor), names(licensedDescriptor), hasLicence);
    }

    /**
     * The server's answer, handed over by the provisioning module (it does the asking; Core
     * only remembers and switches off what is no longer served). Also carries the panel the
     * licence holder's organization chose. Offline, or a refused certificate: nothing changes.
     */
    public static void accept(String openDescriptor, String licensedDescriptor, boolean hasLicence) {
        load();
        // The organization's panel comes with the licence's modules; no certificate, no organization.
        if (!hasLicence || names(licensedDescriptor) != null) PanelProfile.accept(licensedDescriptor);
        Set<String> now = answer(openDescriptor, licensedDescriptor, hasLicence);
        if (now != null) apply(now, FILE);
    }

    /** @param file where to keep the answer; null keeps nothing (tests) */
    static void apply(Set<String> now, Path file) {
        boolean changed = !now.equals(served);
        served = now;
        if (file != null) write(file, now);
        if (!changed) return;
        log.info(TAG + "modules served to this workstation: {}", now);
        // The panels were drawn with the last answer: a module served since then shows now.
        for (Runnable l : LISTENERS.values()) l.run();
    }

    /**
     * Called (on the thread that brings the answer) when what this workstation is served
     * changes. One listener per {@code owner}: registering again replaces it.
     */
    public static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    static void setForTest(Set<String> servedModules) {
        served = servedModules;
        override = Set.of();
        loaded = servedModules != null;
    }
}
