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

package io.astraebio.qtrace.chain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Manages the on-disk layout for a case and its chain.jsonl audit log.
 *
 * Layout:
 *   <exportDir>/case_<sanitized_caseId>/chain.jsonl
 *   <exportDir>/case_<sanitized_caseId>/certs/<certId>.qtcert
 */
public class ChainLog {

    private final Path caseDir;

    public ChainLog(Path exportDir, String caseId) {
        this.caseDir = exportDir.resolve("case_" + sanitize(caseId));
    }

    public Path getCaseDir()  { return caseDir; }
    public Path getCertsDir() { return caseDir.resolve("certs"); }
    public Path getChainLog() { return caseDir.resolve("chain.jsonl"); }

    public String getLatestCertHash() throws IOException {
        JsonObject last = readLastEntry();
        if (last == null) return null;
        return last.has("certificate_sha256") ? last.get("certificate_sha256").getAsString() : null;
    }

    public String getLatestCertId() throws IOException {
        JsonObject last = readLastEntry();
        if (last == null) return null;
        return last.has("certificate_id") ? last.get("certificate_id").getAsString() : null;
    }

    public int getNextChainPosition() throws IOException {
        JsonObject last = readLastEntry();
        if (last == null) return 1;
        return last.has("chain_position") ? last.get("chain_position").getAsInt() + 1 : 1;
    }

    public void appendEntry(int position, String certId, String certSha256,
                            String issuedAt, String parentSha256) throws IOException {
        Files.createDirectories(caseDir);
        JsonObject entry = new JsonObject();
        entry.addProperty("chain_position",    position);
        entry.addProperty("certificate_id",    certId);
        entry.addProperty("certificate_sha256", certSha256);
        entry.addProperty("issued_at",          issuedAt);
        if (parentSha256 != null) {
            entry.addProperty("parent_sha256", parentSha256);
        } else {
            entry.add("parent_sha256", com.google.gson.JsonNull.INSTANCE);
        }
        Files.writeString(getChainLog(), entry + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private JsonObject readLastEntry() throws IOException {
        Path log = getChainLog();
        if (!Files.exists(log)) return null;
        List<String> lines = Files.readAllLines(log);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (!line.isEmpty()) {
                try {
                    return JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static String sanitize(String caseId) {
        return caseId == null ? "_" : caseId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
