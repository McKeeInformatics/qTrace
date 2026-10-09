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

package io.qtrace.provisioning;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * State of the onboarding trunk, in ~/.qTrace/provisioning.json (loader.md § 17;
 * the file of the module before its rename, onboarding.json, is still read). Unknown keys are
 * kept, so a newer version's keys survive a downgrade. (The welcome module keeps its own file.)
 */
public final class ProvisioningState {

    public static final String FILE = "provisioning.json";
    /** The module was called onboarding until 2026-10: its state is picked up, once. */
    static final String LEGACY_FILE = "onboarding.json";

    private final Path file;
    private final JsonObject json;

    private ProvisioningState(Path file, JsonObject json) {
        this.file = file;
        this.json = json;
    }

    /** Reads {@code qtraceDir}/provisioning.json (else the legacy onboarding.json); a missing or corrupt file is a fresh start. */
    public static ProvisioningState load(Path qtraceDir) {
        Path f = qtraceDir.resolve(FILE);
        Path legacy = qtraceDir.resolve(LEGACY_FILE);
        JsonObject o = new JsonObject();
        try {
            Path read = Files.isRegularFile(f) ? f : legacy;
            if (Files.isRegularFile(read)) o = JsonParser.parseString(Files.readString(read)).getAsJsonObject();
        } catch (Exception ignored) {}
        return new ProvisioningState(f, o);
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
