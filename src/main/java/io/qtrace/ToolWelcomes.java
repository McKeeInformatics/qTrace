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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The welcomes of the tools — how to get started with each module — as far as Core is concerned
 * (docs/architecture/welcome-tour.md, layer 3). Core does not play them: the Welcome module
 * does. Core keeps what this workstation already read ({@code ~/.qTrace/welcomes-read.json}, a
 * revision per module), marks in the panel the tools whose welcome is still to be read (a gold
 * square on their button), and asks the Welcome module to open one when the mark is clicked.
 */
public final class ToolWelcomes {

    /** What plays a tool's welcome: the Welcome module. */
    public interface Opener {
        /** Plays the welcome of this module. FX thread. */
        void open(String module);
    }

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".qTrace", "welcomes-read.json");

    private static volatile Set<String> unread = Set.of();
    private static volatile Opener opener;
    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private ToolWelcomes() {}

    // ── Rule (pure) ──────────────────────────────────────────────────────────────

    /**
     * The tools whose welcome is still to be read here.
     *
     * @param written the tools that have a welcome, with its revision
     * @param read    the revision this workstation read, per tool
     * @param present the tools this workstation has, served and installed
     */
    static Set<String> unread(Map<String, Integer> written, Map<String, Integer> read, Set<String> present) {
        Set<String> out = new TreeSet<>();
        written.forEach((module, revision) -> {
            if (!present.contains(module)) return;
            Integer seen = read.get(module);
            if (seen == null || seen < revision) out.add(module);
        });
        return out;
    }

    // ── On disk ──────────────────────────────────────────────────────────────────

    static Map<String, Integer> read(Path file) {
        Map<String, Integer> out = new TreeMap<>();
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("read");
            for (Map.Entry<String, JsonElement> e : o.entrySet()) out.put(e.getKey(), e.getValue().getAsInt());
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    static void write(Path file, Map<String, Integer> read) {
        try {
            JsonObject revisions = new JsonObject();
            new TreeMap<>(read).forEach(revisions::addProperty);
            JsonObject o = new JsonObject();
            o.add("read", revisions);
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString());
        } catch (Exception ignored) {}
    }

    // ── For the Welcome module ───────────────────────────────────────────────────

    /** The tools of {@code written} still to be read on this workstation, among the ones it has. */
    public static Set<String> unreadHere(Map<String, Integer> written, Set<String> present) {
        return unread(written, read(FILE), present);
    }

    /** This tool's welcome was read (or closed) at this revision: its mark goes away. */
    public static void markRead(String module, int revision) {
        Map<String, Integer> read = read(FILE);
        read.merge(module, revision, Math::max);
        write(FILE, read);
        Set<String> left = new TreeSet<>(unread);
        if (left.remove(module)) setUnread(left);
    }

    /** Says which tools are marked in the panel. Listeners are told only when it changes. */
    public static void setUnread(Set<String> modules) {
        Set<String> now = Set.copyOf(modules);
        if (now.equals(unread)) return;
        unread = now;
        for (Runnable l : LISTENERS.values()) l.run();
    }

    public static void register(Opener o) {
        opener = o;
        for (Runnable l : LISTENERS.values()) l.run();
    }

    // ── For the panel ────────────────────────────────────────────────────────────

    /** True when this tool's button carries the mark: a welcome to read, and a module to play it. */
    public static boolean isMarked(String module) {
        return opener != null && unread.contains(module);
    }

    static boolean isUnread(String module) {
        return unread.contains(module);
    }

    /** Opens the tool's welcome. @return false when no module plays welcomes */
    public static boolean open(String module) {
        Opener o = opener;
        if (o == null) return false;
        o.open(module);
        return true;
    }

    /** Called (on the calling thread) when the marks change. One listener per owner. */
    public static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    static void clear() {
        unread = Set.of();
        opener = null;
        LISTENERS.clear();
    }
}
