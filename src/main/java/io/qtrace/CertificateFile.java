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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Reading and rewriting the jwt of a .qtlicense (JSON envelope {jwt, sk_*} or bare JWT). No UI. */
public final class CertificateFile {

    private CertificateFile() {}

    /** The jwt of a .qtlicense; null if there is none. */
    public static String jwtOf(String content) {
        String c = content.strip();
        if (!c.startsWith("{")) return c.isEmpty() ? null : c;
        JsonObject o = JsonParser.parseString(c).getAsJsonObject();
        return o.has("jwt") ? o.get("jwt").getAsString() : null;
    }

    /** The same .qtlicense with a new jwt; everything else (the encrypted key) unchanged. */
    public static String replaceJwt(String content, String newJwt) {
        String c = content.strip();
        if (!c.startsWith("{")) return newJwt;
        JsonObject o = JsonParser.parseString(c).getAsJsonObject();
        o.addProperty("jwt", newJwt);
        return new GsonBuilder().setPrettyPrinting().create().toJson(o);
    }
}
