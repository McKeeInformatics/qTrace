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

package io.qtrace.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.SecretFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The last artefacts the player opened (📂 Open › Recent), the latest first, in
 * {@code ~/.qTrace/player-recents.json}. Only what loaded is kept. The file is private to its
 * owner: a share link is a key to the record. No JavaFX: unit-tested.
 */
final class PlayerRecents {

    static final int MAX = 5;

    static final Path FILE = Path.of(System.getProperty("user.home"), ".qTrace", "player-recents.json");

    /** What the source is, as the menu says it. */
    enum Kind {
        FILE("record"), ONLINE("qtrace.ca record"), SHARED("shared link"), WORKFLOW("workflow");

        final String label;

        Kind(String label) { this.label = label; }
    }

    /** @param source what the player was given, opened again as is; {@code openedAt} in epoch ms */
    record Entry(String source, Kind kind, String title, long openedAt) {}

    private PlayerRecents() {}

    /** {@code list} with {@code entry} first: once only, {@link #MAX} at most. */
    static List<Entry> remember(List<Entry> list, Entry entry) {
        String key = keyOf(entry.source());
        List<Entry> out = new ArrayList<>();
        out.add(entry);
        for (Entry e : list) {
            if (out.size() == MAX) break;
            if (!keyOf(e.source()).equals(key)) out.add(e);
        }
        return out;
    }

    /**
     * Two sources are the same artefact when they have the same key: a file by its normalised
     * path, a record by its ID (a /verify/ link names one), a workflow's packet by ID and packet,
     * a share link by its token.
     */
    static String keyOf(String source) {
        String s = source.strip();
        RecordSource parsed = RecordSource.parse(s);
        if (parsed.local()) {
            try { return "file:" + Path.of(s).toAbsolutePath().normalize(); }
            catch (RuntimeException e) { return "file:" + s; }
        }
        if (parsed.token() != null) return "share:" + parsed.token();
        String packet = s.startsWith(WorkflowSource.ID_PREFIX) ? WorkflowSource.packetOf(s) : null;
        return "id:" + parsed.certId() + (packet != null ? "#" + packet : "");
    }

    static Kind kindOf(String source, boolean workflow) {
        if (workflow) return Kind.WORKFLOW;
        RecordSource parsed = RecordSource.parse(source);
        if (parsed.local()) return Kind.FILE;
        return parsed.token() != null ? Kind.SHARED : Kind.ONLINE;
    }

    /** The workflow's title, else the image's name, else the file's name or what was given. */
    static String titleOf(String source, String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String title = string(root, "title");
            if (WorkflowSource.isWorkflow(json) && title != null) return title;
            if (root.has("image") && root.get("image").isJsonObject()) {
                String name = string(root.getAsJsonObject("image"), "name");
                if (name != null) return name;
            }
        } catch (RuntimeException ignored) {}
        String s = source.strip();
        if (RecordSource.parse(s).local()) {
            Path name = Path.of(s).getFileName();
            if (name != null) return name.toString();
        }
        return s;
    }

    /** A local file must still be there; what qtrace.ca serves is asked for at the click. */
    static boolean available(Entry entry) {
        if (!RecordSource.parse(entry.source()).local()) return true;
        try { return Files.isRegularFile(Path.of(entry.source().strip())); }
        catch (RuntimeException e) { return false; }
    }

    static String ago(long then, long now) {
        long min = Math.max(0, now - then) / 60_000;
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        if (min < 24 * 60) return (min / 60) + " h ago";
        if (min < 48 * 60) return "yesterday";
        return DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
            .format(Instant.ofEpochMilli(then).atZone(ZoneId.systemDefault()));
    }

    /** Empty when the file is missing or unreadable; an entry it cannot read is left out. */
    static List<Entry> read(Path file) {
        List<Entry> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(file)) return out;
            JsonArray array = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            for (JsonElement el : array) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String source = string(o, "source");
                if (source == null) continue;
                Kind kind;
                try { kind = Kind.valueOf(string(o, "kind")); }
                catch (RuntimeException e) { kind = kindOf(source, false); }
                String title = string(o, "title");
                long at = o.has("openedAt") && o.get("openedAt").isJsonPrimitive() ? o.get("openedAt").getAsLong() : 0;
                out.add(new Entry(source, kind, title != null ? title : source, at));
                if (out.size() == MAX) break;
            }
        } catch (Exception e) {
            System.err.println("[qTrace] player recents not read: " + e.getMessage());
            return new ArrayList<>();
        }
        return out;
    }

    static void write(Path file, List<Entry> list) throws IOException {
        JsonArray array = new JsonArray();
        for (Entry e : list) {
            JsonObject o = new JsonObject();
            o.addProperty("source", e.source());
            o.addProperty("kind", e.kind().name());
            o.addProperty("title", e.title());
            o.addProperty("openedAt", e.openedAt());
            array.add(o);
        }
        SecretFiles.write(file, array.toString());
    }

    private static String string(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        String v = o.get(key).getAsString();
        return v.isBlank() ? null : v;
    }
}
