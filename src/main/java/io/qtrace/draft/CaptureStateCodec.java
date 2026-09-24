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

package io.qtrace.draft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;

/**
 * JSON form of a {@link CaptureState}, versioned so a draft written by one qTrace release
 * is never half-understood by another: an unknown {@code state_version} is refused and the
 * draft is left alone rather than restored wrongly.
 */
public final class CaptureStateCodec {

    public static final int STATE_VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
        .registerTypeAdapter(byte[].class, new BytesAdapter().nullSafe())
        .serializeSpecialFloatingPointValues()
        .disableHtmlEscaping()
        .create();

    private CaptureStateCodec() {}

    public static JsonObject toJson(CaptureState state) {
        JsonObject json = GSON.toJsonTree(state).getAsJsonObject();
        json.addProperty("state_version", STATE_VERSION);
        return json;
    }

    public static CaptureState fromJson(JsonObject json) {
        int version = json.has("state_version") && json.get("state_version").isJsonPrimitive()
            ? json.get("state_version").getAsInt() : -1;
        if (version != STATE_VERSION)
            throw new IllegalArgumentException("Unsupported capture state version: " + version);
        JsonObject copy = json.deepCopy();
        copy.remove("state_version");
        CaptureState state = GSON.fromJson(copy, CaptureState.class);
        if (state == null) throw new IllegalArgumentException("Empty capture state");
        return state;
    }

    private static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value.toString());
        }
        @Override public Instant read(JsonReader in) throws IOException {
            return Instant.parse(in.nextString());
        }
    }

    /** Imported companion files can be large — base64, not a JSON array of numbers. */
    private static final class BytesAdapter extends TypeAdapter<byte[]> {
        @Override public void write(JsonWriter out, byte[] value) throws IOException {
            out.value(Base64.getEncoder().encodeToString(value));
        }
        @Override public byte[] read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            return Base64.getDecoder().decode(in.nextString());
        }
    }
}
