package io.qtrace;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QTraceExporterGitBlockTest {

    @Test
    void sessionGitBlock_isOmittedWhenThereIsNoCommit() {
        assertNull(QTraceExporter.gitBlock(null, Path.of("/tmp/out")));
        assertNull(QTraceExporter.gitBlock(" ", Path.of("/tmp/out")));
    }

    @Test
    void sessionGitBlock_carriesTheCommitWhenThereIsOne() {
        JsonObject g = QTraceExporter.gitBlock("abc1234", Path.of("/tmp/out"));
        assertEquals("abc1234", g.get("commit").getAsString());
    }
}
