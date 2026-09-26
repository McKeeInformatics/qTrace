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

package io.qtrace.onboarding;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * State of the onboarding trunk, in ~/.qTrace/onboarding.json (loader.md § 17). Unknown keys are
 * kept, so a newer version's keys survive a downgrade. (The welcome module keeps its own file.)
 */
public final class OnboardingState {

    public static final String FILE = "onboarding.json";

    private final Path file;
    private final JsonObject json;

    private OnboardingState(Path file, JsonObject json) {
        this.file = file;
        this.json = json;
    }

    /** Reads {@code qtraceDir}/onboarding.json; a missing or corrupt file is a fresh start. */
    public static OnboardingState load(Path qtraceDir) {
        Path f = qtraceDir.resolve(FILE);
        JsonObject o = new JsonObject();
        try {
            if (Files.isRegularFile(f)) o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        } catch (Exception ignored) {}
        return new OnboardingState(f, o);
    }

    /** The trunk shows up on a machine with no license, until the user finished or skipped it. */
    public boolean shouldShowTrunk(String licensePath) {
        boolean licensed = licensePath != null && !licensePath.isBlank();
        return !licensed && !flag("trunkDone");
    }

    public void markTrunkDone() {
        json.addProperty("trunkDone", true);
        save();
    }

    public boolean flag(String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsBoolean();
    }

    public void setFlag(String key, boolean value) {
        json.addProperty(key, value);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (Exception ignored) {
            // Worst case the trunk shows again next start — never worth an error dialog.
        }
    }
}
