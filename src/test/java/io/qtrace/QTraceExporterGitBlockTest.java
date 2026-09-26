package io.qtrace;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QTraceExporterGitBlockTest {

    @Test
    void sessionGitBlock_isOmittedWhenThereIsNoCommit() {
        assertNull(QTraceExporter.gitBlock(null));
        assertNull(QTraceExporter.gitBlock(" "));
    }

    @Test
    void sessionGitBlock_pointsAtTheCommitItExtends_withoutLocalPaths() {
        JsonObject g = QTraceExporter.gitBlock("abc1234");
        assertEquals("abc1234", g.get("parent_commit").getAsString());
        assertEquals(1, g.size(), "no repo_dir: a local path has no place in a shared record");
    }
}
