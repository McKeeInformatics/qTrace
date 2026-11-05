package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkspacePushRequestTest {

    @Test
    void aRequestBuiltTheOldWayCarriesNoObjectClassifierFiles() {
        var r = new WorkspacePush.Request(null, null, null, Path.of("a.qtrace"), List.of(), null, List.of(), null, "qtb_1", null);
        assertTrue(r.objectClassifiers().isEmpty());
        assertEquals("qtb_1", r.recordId());
    }

    @Test
    void aRequestCarriesTheFilesItIsGiven() {
        var f = new ObjectClassifierFiles.PushFile("OC", "OC@abc.json", "abc", Path.of("OC@abc.json"));
        var r = new WorkspacePush.Request(null, null, null, Path.of("a.qtrace"), List.of(), null, List.of(), null, "qtb_1", null, List.of(f));
        assertEquals(List.of(f), List.copyOf(r.objectClassifiers()));
    }
}
