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

import com.google.gson.JsonObject;
import io.qtrace.chain.Ulid;

/**
 * A record whose stamp produced no certificate — a basic account, or a workstation without
 * Compliance. It gets an identifier {@code qtb_<ULID>} (b = basic; a certified record's
 * {@code qtc_…} is the .qtcert's) and the session written in the .qtrace says so:
 * <pre>"record": { "id": "qtb_…", "identity": "self_declared" }</pre>
 * beside the stamp ({@code validation}), never inside it: {@link ValidationStamp#canonicalPayload()}
 * is what a stamp signs and does not change.
 */
public final class BasicRecord {

    public static final String PREFIX = "qtb_";
    public static final String SELF_DECLARED = "self_declared";

    private BasicRecord() {}

    public static String newId() {
        return PREFIX + Ulid.generate();
    }

    /** qtb_ followed by an ULID in Crockford base 32 (the portal's rule). */
    public static boolean isId(String id) {
        return id != null && id.matches("^qtb_[0-9A-HJKMNP-TV-Z]{26}$");
    }

    /** A stamped session is a basic record when no certificate came with its stamp. */
    public static boolean applies(boolean stamped, boolean certificateProduced) {
        return stamped && !certificateProduced;
    }

    /** The {@code record} object of a session. */
    public static JsonObject block(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("identity", SELF_DECLARED);
        return o;
    }

    /** The basic id a session carries; null when it has none (a certified or an unstamped session). */
    public static String idOf(JsonObject session) {
        try {
            if (session == null || !session.has("record") || !session.get("record").isJsonObject()) return null;
            JsonObject rec = session.getAsJsonObject("record");
            if (!rec.has("id") || !rec.get("id").isJsonPrimitive()) return null;
            String id = rec.get("id").getAsString();
            return isId(id) ? id : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The basic id of the .qtrace's latest stamp; null when that stamp has none (it was
     * certified) or there is no stamp. Sessions autosaved after the stamp are not stamps and do
     * not hide it — the record is the one the last stamp made.
     */
    public static String idOfLatestStamp(JsonObject qtraceRoot) {
        try {
            return idOf(StampIntegrity.latestValidatedSession(qtraceRoot));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
