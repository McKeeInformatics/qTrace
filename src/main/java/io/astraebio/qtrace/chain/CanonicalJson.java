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

import java.util.Map;
import java.util.TreeMap;

/**
 * RFC 8785 (JSON Canonicalization Scheme) serializer for flat string maps.
 *
 * Rules applied:
 *   - Keys sorted in ascending Unicode code-unit order (Java TreeMap natural order).
 *   - No insignificant whitespace.
 *   - String values escaped per RFC 8259 section 7, with control chars unicode-escaped.
 *   - Null values serialized as JSON null.
 *
 * This subset is sufficient for the ValidationStamp canonical payload, which
 * contains only string-typed fields. A full RFC 8785 implementation would also
 * handle nested objects, arrays, and IEEE 754 number serialization.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /**
     * Serializes a string-to-string map to RFC 8785 canonical JSON.
     * Null values are rendered as JSON {@code null}.
     */
    public static String of(Map<String, String> fields) {
        TreeMap<String, String> sorted = new TreeMap<>(fields);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : sorted.entrySet()) {
            if (!first) sb.append(",");
            sb.append(jsonString(entry.getKey())).append(":");
            String val = entry.getValue();
            sb.append(val != null ? jsonString(val) : "null");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
