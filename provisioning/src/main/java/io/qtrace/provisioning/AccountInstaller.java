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
import io.qtrace.Account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the account token received through "Sign in" for a basic account (no identity
 * certificate): {@code {"jwt":"…"}} in qtrace.qtaccount, read by Core's {@link Account}.
 * The licence file is never touched.
 */
public final class AccountInstaller {

    private AccountInstaller() {}

    /** Writes {@code qtraceDir}/qtrace.qtaccount (a previous one is kept as .bak) and returns its path. */
    public static Path write(String jwt, Path qtraceDir) throws IOException {
        if (jwt == null || jwt.isBlank()) throw new IllegalArgumentException("Account token is empty");
        JsonObject env = new JsonObject();
        env.addProperty("jwt", jwt.strip());

        Files.createDirectories(qtraceDir);
        Path target = qtraceDir.resolve(Account.FILE);
        if (Files.exists(target))
            Files.copy(target, qtraceDir.resolve(Account.FILE + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(env));
        return target;
    }
}
