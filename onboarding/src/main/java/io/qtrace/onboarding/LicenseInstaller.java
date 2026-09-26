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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the .qtlicense received through "Sign in" — the same JSON envelope
 * {jwt, sk_enc, sk_salt, sk_iv} as the file the portal downloads, read unchanged by Core,
 * Compliance and the loader.
 */
public final class LicenseInstaller {

    public static final String FILE = "qtrace.qtlicense";

    private LicenseInstaller() {}

    /** Writes {@code qtraceDir}/qtrace.qtlicense (a previous one is kept as .bak) and returns its path. */
    public static Path write(String envelopeJson, Path qtraceDir) throws IOException {
        JsonObject env;
        try {
            env = JsonParser.parseString(envelopeJson).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a license envelope", e);
        }
        if (!env.has("jwt") || env.get("jwt").getAsString().isBlank())
            throw new IllegalArgumentException("License envelope without jwt");

        Files.createDirectories(qtraceDir);
        Path target = qtraceDir.resolve(FILE);
        if (Files.exists(target))
            Files.copy(target, qtraceDir.resolve(FILE + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(env));
        return target;
    }
}
