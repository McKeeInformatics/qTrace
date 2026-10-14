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

package io.qtrace.welcome;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CertificateWhoTest {

    private static String jwt(String payload) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
            + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void readsTheNameAndTheInstitution() {
        var who = CertificateWho.of(jwt("{\"name\":\"Dr. Jane Demo\",\"institution\":\"Demo Hospital\"}"));
        assertEquals("Dr. Jane Demo", who.name());
        assertEquals("Demo Hospital", who.institution());
    }

    @Test
    void missingFieldsAreEmpty() {
        var who = CertificateWho.of(jwt("{\"name\":\"A. Moreau\"}"));
        assertEquals("A. Moreau", who.name());
        assertEquals("", who.institution());
    }

    @Test
    void anUnreadableTokenIsNull() {
        assertNull(CertificateWho.of(null));
        assertNull(CertificateWho.of("not-a-token"));
        assertNull(CertificateWho.of("a.b.c"));
    }
}
