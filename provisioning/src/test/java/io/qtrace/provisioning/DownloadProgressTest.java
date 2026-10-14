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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadProgressTest {

    private HttpServer server;
    private byte[] file;

    @BeforeEach
    void start() throws Exception {
        file = new byte[500_000];
        for (int i = 0; i < file.length; i++) file[i] = (byte) i;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/m.qtjar", ex -> {
            ex.sendResponseHeaders(200, file.length);
            try (var out = ex.getResponseBody()) { out.write(file); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void reportsHowFarTheDownloadIsAndReturnsTheFile() throws Exception {
        List<Double> seen = new ArrayList<>();
        byte[] got = DownloadProgress.get(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/m.qtjar", null, seen::add);

        assertArrayEquals(file, got);
        assertTrue(seen.size() > 2, "progress came in several steps: " + seen.size());
        for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) >= seen.get(i - 1), "never goes back");
        assertEquals(1.0, seen.get(seen.size() - 1), 1e-9);
    }
}
