package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRecordsTest {

    @TempDir Path dir;

    private void trace(String file, String imageName) throws Exception {
        Files.writeString(dir.resolve(file),
            "{\"qtrace_format\":\"2.0\",\"image\":{\"name\":\"" + imageName + "\"},\"sessions\":[]}");
    }

    @Test
    void everyProjectImageIsARowWithItsTraceWhenThereIsOne() throws Exception {
        trace("Core_01.qtrace", "Core_01.ome.tif");
        ProjectRecords.Scan scan = ProjectRecords.scan(List.of("Core_01.ome.tif", "Core_02.ome.tif"), dir.toFile());

        assertEquals(2, scan.rows().size());
        assertEquals("Core_01.ome.tif", scan.rows().get(0).imageName());
        assertNotNull(scan.rows().get(0).qtrace());
        assertEquals("Core_01.qtrace", scan.rows().get(0).qtraceFile().getName());
        assertNull(scan.rows().get(1).qtrace());
        assertNull(scan.rows().get(1).qtraceFile());
        assertEquals(1, scan.qtraceCount());
    }

    @Test
    void aTraceWhoseSessionCarriesABasicRecordIsReadLikeAnyOther() throws Exception {
        Files.writeString(dir.resolve("Core_01.qtrace"),
            "{\"qtrace_format\":\"2.0\",\"image\":{\"name\":\"Core_01.ome.tif\"},\"sessions\":[{\"session_id\":\"s\","
          + "\"validation_state\":\"stamped\",\"validation\":{\"validator\":\"Ada\"},"
          + "\"record\":{\"id\":\"qtb_01ARZ3NDEKTSV4RRFFQ69G5FAV\",\"identity\":\"self_declared\"}}]}");
        ProjectRecords.Scan scan = ProjectRecords.scan(List.of("Core_01.ome.tif"), dir.toFile());

        assertNotNull(scan.rows().get(0).qtrace());
        assertEquals(1, scan.qtraceCount());
    }

    @Test
    void aTraceWhoseImageLeftTheProjectIsStillListedAfterTheProjectImages() throws Exception {
        trace("Core_01.qtrace", "Core_01.ome.tif");
        trace("Old.qtrace", "Old.ome.tif");
        ProjectRecords.Scan scan = ProjectRecords.scan(List.of("Core_01.ome.tif"), dir.toFile());

        assertEquals(List.of("Core_01.ome.tif", "Old.ome.tif"),
            scan.rows().stream().map(ProjectRecords.Row::imageName).toList());
    }

    @Test
    void aProjectNameThatContainsTheTraceImageNameMatches() throws Exception {
        trace("Core_01.qtrace", "Core_01");
        ProjectRecords.Scan scan = ProjectRecords.scan(List.of("Core_01.ome.tif"), dir.toFile());

        assertEquals(1, scan.rows().size());
        assertNotNull(scan.rows().get(0).qtrace());
    }

    @Test
    void anUnreadableTraceIsSkipped() throws Exception {
        Files.writeString(dir.resolve("Broken.qtrace"), "{ not json");
        ProjectRecords.Scan scan = ProjectRecords.scan(List.of("Core_01.ome.tif"), dir.toFile());

        assertEquals(1, scan.rows().size());
        assertNull(scan.rows().get(0).qtrace());
        assertEquals(0, scan.qtraceCount());
    }

    @Test
    void withoutAnExportFolderTheProjectImagesAreListedBare() {
        ProjectRecords.Scan scan = ProjectRecords.scan(List.of("Core_01.ome.tif"), null);

        assertEquals(1, scan.rows().size());
        assertNull(scan.dirPath());
        assertTrue(scan.rows().stream().allMatch(r -> r.qtrace() == null));
    }
}
