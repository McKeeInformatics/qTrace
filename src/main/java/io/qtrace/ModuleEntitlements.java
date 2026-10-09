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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

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
    private static final Set<String> FREE = Set.of("core", "onboarding", "loader");

    private static final Path DIR      = Path.of(System.getProperty("user.home"), ".qTrace");
    private static final Path FILE     = DIR.resolve("modules-entitled.json");
    /** Developer override, one module name per line: keeps a locally built module switched on. */
    private static final Path OVERRIDE = DIR.resolve("dev-modules");

    private static volatile Set<String> served;      // null = no answer known
    private static volatile Set<String> override;
    private static volatile boolean loaded;

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
     * Async, safe: asks the server what this workstation is served — the modules open to anyone,
     * and with a certificate the ones it includes — and remembers the answer. Offline, or a
     * refused certificate: the last answer stands.
     */
    public static void refresh() {
        load();
        String jwt = QTraceUpdater.licenseJwt();
        CompletableFuture.runAsync(() -> {
            try {
                Set<String> open = names(fetch(QTraceUpdater.openModulesUrl(), null));
                String descriptor = jwt == null ? null : fetch(QTraceUpdater.modulesUrl(), jwt);
                Set<String> licensed = names(descriptor);
                // The organization's panel comes with the licence's modules; no certificate, no organization.
                if (jwt == null || licensed != null) PanelProfile.accept(descriptor);
                Set<String> now = combine(open, licensed, jwt != null);
                if (now == null) return;
                if (!now.equals(served)) log.info(TAG + "modules served to this workstation: {}", now);
                served = now;
                write(FILE, now);
            } catch (Exception e) {
                log.info(TAG + "entitlement check skipped: {}", e.toString());
            }
        });
    }

    /** A descriptor URL's answer; null when it could not be read. */
    private static String fetch(String url, String jwt) {
        try {
            return new String(QTraceUpdater.httpGetBytes(url, jwt), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.info(TAG + "{} unavailable: {}", url, e.toString());
            return null;
        }
    }

    static void setForTest(Set<String> servedModules) {
        served = servedModules;
        override = Set.of();
        loaded = servedModules != null;
    }
}
