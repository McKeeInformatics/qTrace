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
 */

package io.qtrace.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The player's own settings, in {@code ~/.qTrace/player.json} (one file per module, Core's
 * config.json is never touched). Reading never fails: a missing or damaged file means the
 * defaults, and never creates anything. Writing is atomic; a choice that cannot be written
 * still holds for this session. No JavaFX: unit-tested.
 */
final class PlayerSettings {

    static final String SAVE_ON_TARGET_CHANGE = "saveOnTargetChange";

    private final Path file;
    private Boolean saveOnTargetChange; // the choice made in this session, written or not

    PlayerSettings(Path file) { this.file = file; }

    static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".qTrace", "player.json");
    }

    /** Save each image before the batch moves to the next target. Checked by default. */
    boolean saveOnTargetChange() {
        if (saveOnTargetChange != null) return saveOnTargetChange;
        JsonElement v = read().get(SAVE_ON_TARGET_CHANGE);
        if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) return v.getAsBoolean();
        return true;
    }

    void setSaveOnTargetChange(boolean value) {
        saveOnTargetChange = value;
        JsonObject json = read(); // keeps what other versions of the player wrote
        json.addProperty(SAVE_ON_TARGET_CHANGE, value);
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "player", ".json.tmp");
            try {
                Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ignored) {
            // the choice then lasts for this session only
        }
    }

    private JsonObject read() {
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (e.isJsonObject()) return e.getAsJsonObject();
        } catch (Exception ignored) {
            // absent or damaged: defaults
        }
        return new JsonObject();
    }
}
