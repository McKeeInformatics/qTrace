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

package io.qtrace.player;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A record opened by its ID: its object classifier files come from qtrace.ca, byte for byte. */
class ObjectClassifierDownloadTest {

    @TempDir Path dir;

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private volatile int listStatus = 200;
    private volatile String listBody;
    private volatile String listedPath;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        byte[] body;
        int status = 200;
        if (path.endsWith("/object-classifiers")) {
            listedPath = path;
            status = listStatus;
            body = (listBody != null ? listBody : listing()).getBytes(StandardCharsets.UTF_8);
        } else if (files.containsKey(path.substring("/files/".length()))) {
            body = files.get(path.substring("/files/".length()));
        } else {
            status = 404;
            body = "{}".getBytes(StandardCharsets.UTF_8);
        }
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private String listing() {
        StringBuilder sb = new StringBuilder("[");
        for (String name : files.keySet()) sb.append(sb.length() > 1 ? "," : "").append("{\"name\":\"").append(name).append("\",\"url\":\"").append(base).append("/files/").append(name).append("\"}");
        return sb.append("]").toString();
    }

    @Test
    void everyListedFileIsDownloadedByteForByte() throws Exception {
        byte[] a = "{\"threshold\":120.0,\"é\":1}".getBytes(StandardCharsets.UTF_8);
        files.put("OC-A@aaaaaaaaaaaa.json", a);
        files.put("OC-B@bbbbbbbbbbbb.json", new byte[] {1, 2, 3, (byte) 0xFF});
        var result = QTraceReplayEngine.downloadObjectClassifiers(base, "qtc_01ABC", null, dir);
        assertEquals(Set.of("OC-A@aaaaaaaaaaaa.json", "OC-B@bbbbbbbbbbbb.json"), Set.copyOf(result.succeeded()));
        assertTrue(result.failed().isEmpty());
        assertArrayEquals(a, Files.readAllBytes(dir.resolve("OC-A@aaaaaaaaaaaa.json")));
        assertArrayEquals(new byte[] {1, 2, 3, (byte) 0xFF}, Files.readAllBytes(dir.resolve("OC-B@bbbbbbbbbbbb.json")));
        assertEquals("/api/verify/qtc_01ABC/object-classifiers", listedPath);
    }

    @Test
    void aRecordWithNoneListsNothing() throws Exception {
        var result = QTraceReplayEngine.downloadObjectClassifiers(base, "qtc_01ABC", null, dir);
        assertTrue(result.succeeded().isEmpty());
        assertTrue(result.failed().isEmpty());
    }

    @Test
    void aFileThatCannotBeFetchedIsReportedAndTheOthersAreStillTaken() throws Exception {
        files.put("OC-A@aaaaaaaaaaaa.json", "{}".getBytes(StandardCharsets.UTF_8));
        listBody = "[{\"name\":\"OC-A@aaaaaaaaaaaa.json\",\"url\":\"" + base + "/files/OC-A@aaaaaaaaaaaa.json\"},"
            + "{\"name\":\"OC-GONE@cccccccccccc.json\",\"url\":\"" + base + "/files/OC-GONE@cccccccccccc.json\"}]";
        var result = QTraceReplayEngine.downloadObjectClassifiers(base, "qtc_01ABC", null, dir);
        assertEquals(List.of("OC-A@aaaaaaaaaaaa.json"), result.succeeded());
        assertEquals(Set.of("OC-GONE@cccccccccccc.json"), result.failed().keySet());
        assertTrue(result.failed().get("OC-GONE@cccccccccccc.json").contains("404"));
    }

    @Test
    void aNameThatIsNotAPlainFileNameIsNeverWrittenOutsideTheFolder() throws Exception {
        files.put("x.json", "{}".getBytes(StandardCharsets.UTF_8));
        listBody = "[{\"name\":\"../escape.json\",\"url\":\"" + base + "/files/x.json\"}]";
        var result = QTraceReplayEngine.downloadObjectClassifiers(base, "qtc_01ABC", null, dir);
        assertTrue(result.succeeded().isEmpty());
        assertEquals(Set.of("../escape.json"), result.failed().keySet());
        assertFalse(Files.exists(dir.getParent().resolve("escape.json")));
    }

    @Test
    void whenTheListCannotBeReadTheCallerIsToldNotLeftWithAnEmptyListThatReadsAsNone() {
        listStatus = 502;
        listBody = "{\"error\":\"Cannot list the files, try again\"}";
        assertThrows(IOException.class, () -> QTraceReplayEngine.downloadObjectClassifiers(base, "qtc_01ABC", null, dir));
    }
}
