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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.qtrace.ObjectClassifierFiles;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workflow opened by its ID ({@code qtw_…}, as a training step or the editor's Play gives it): its object
 * classifier files come from qtrace.ca (GET /api/workflows/<id>/object-classifiers), then each is checked
 * against the sha256 the .qtflow records before the replay may use it.
 */
class WorkflowObjectClassifierDownloadTest {

    private static final byte[] CK19 = ("{\"object_classifier_type\":\"SimpleClassifier\",\"threshold\":120.0}").getBytes(StandardCharsets.UTF_8);
    private static final JsonObject REC = ObjectClassifierFiles.record("OC-CK19", CK19, Instant.parse("2026-11-04T10:00:00Z"), "alice", true);
    private static final String FILE = REC.get("classifier_file").getAsString();

    @TempDir Path dir;

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private volatile String listedPath;
    private volatile int listStatus = 200;

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
            StringBuilder sb = new StringBuilder("[");
            for (String name : files.keySet())
                sb.append(sb.length() > 1 ? "," : "").append("{\"name\":\"").append(name).append("\",\"url\":\"").append(base).append("/files/").append(name).append("\"}");
            body = (status == 200 ? sb.append("]").toString() : "{\"error\":\"You do not have access to this workflow\"}").getBytes(StandardCharsets.UTF_8);
        } else if (path.startsWith("/files/") && files.containsKey(path.substring(7))) {
            body = files.get(path.substring(7));
        } else {
            status = 404;
            body = "{}".getBytes(StandardCharsets.UTF_8);
        }
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** A .qtflow whose one packet records the CK19 classifier. */
    private static String workflow() {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "workflow");
        root.addProperty("workflow_id", "qtw_01ABC");
        JsonObject p = new JsonObject();
        p.addProperty("session_id", "packet-1");
        p.add("steps", new JsonArray());
        JsonArray arr = new JsonArray();
        arr.add(REC);
        p.add("object_classifiers", arr);
        JsonArray sessions = new JsonArray();
        sessions.add(p);
        root.add("sessions", sessions);
        return root.toString();
    }

    private ObjectClassifierReplay.Downloader downloader() {
        return (id, to) -> QTraceReplayEngine.downloadObjectClassifiers(base, id, null, to);
    }

    @Test
    void theFilesAreAskedOfTheWorkflowsOwnRoute() throws Exception {
        files.put(FILE, CK19);
        QTraceReplayEngine.downloadObjectClassifiers(base, "qtw_01ABC", null, dir);
        assertEquals("/api/workflows/qtw_01ABC/object-classifiers", listedPath);
    }

    @Test
    void aDownloadedFileWithTheRecordedSha256IsHandedToTheReplay() {
        files.put(FILE, CK19);
        String source = "qtw_01ABC#packet-1";
        var res = ObjectClassifierReplay.resolve(source, workflow(), dir, QTraceReplayEngine.extractCertId(source), downloader());
        assertEquals(List.of("OC-CK19"), res.verified());
        assertEquals(ObjectClassifierReplay.Level.OK, res.level());
        assertTrue(java.nio.file.Files.isRegularFile(dir.resolve(ObjectClassifierReplay.DIR).resolve(FILE)));
    }

    @Test
    void aDownloadedFileThatIsAnotherClassifierIsNeverReplayed() {
        files.put(FILE, "{\"object_classifier_type\":\"SimpleClassifier\",\"threshold\":121.0}".getBytes(StandardCharsets.UTF_8));
        var res = ObjectClassifierReplay.resolve("qtw_01ABC", workflow(), dir, "qtw_01ABC", downloader());
        assertEquals(ObjectClassifierReplay.Level.BAD, res.level());
        assertTrue(res.differs().containsKey("OC-CK19"), res.summary());
        assertFalse(java.nio.file.Files.exists(dir.resolve(ObjectClassifierReplay.DIR).resolve(FILE)));
    }

    @Test
    void aWorkflowPublishedBeforeTheFilesTravelledSaysTheyAreMissing() {
        // Published by an editor from before: qtrace.ca lists none.
        var res = ObjectClassifierReplay.resolve("qtw_01ABC", workflow(), dir, "qtw_01ABC", downloader());
        assertEquals(ObjectClassifierReplay.Level.BAD, res.level());
        assertTrue(res.missing().containsKey("OC-CK19"), res.summary());
    }

    @Test
    void aQtraceThatDoesNotServeTheFilesYetIsSaidSoNotTakenForAnUnknownWorkflow() {
        listStatus = 404;
        var res = ObjectClassifierReplay.resolve("qtw_01ABC", workflow(), dir, "qtw_01ABC", downloader());
        String why = res.missing().get("OC-CK19");
        assertNotNull(why, res.summary());
        assertTrue(why.contains("does not serve"), why);
    }
}
