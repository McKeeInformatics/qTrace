package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportedObjectFileRecordTest {

    private static final Instant T = Instant.parse("2026-10-16T10:00:00Z");

    private static ImportedObjectFileRecord record(List<ImportAttachment> attachments) {
        return new ImportedObjectFileRecord("regions.geojson", "img.import_1.geojson", "/tmp/regions.geojson",
            new byte[]{1, 2}, "sha", 3, T, "romain", "Tumour regions drawn by model X", attachments);
    }

    @Test
    void companionsAreTheImportedFileThenItsAttachmentsInOrder() {
        var a = new ImportAttachment("train.py", "img.import_1.gen_1.train.py", "s1", new byte[]{9});
        var b = new ImportAttachment("env.yml", "img.import_1.gen_2.env.yml", "s2", new byte[]{8, 7});
        var names = record(List.of(a, b)).companions().stream().map(ImportedObjectFileRecord.Companion::filename).toList();
        assertEquals(List.of("img.import_1.geojson", "img.import_1.gen_1.train.py", "img.import_1.gen_2.env.yml"), names);
    }

    @Test
    void totalBytesCountsTheImportedFileAndEveryAttachment() {
        var a = new ImportAttachment("train.py", "x.py", "s1", new byte[10]);
        assertEquals(12, record(List.of(a)).totalBytes());
    }

    @Test
    void aRecordWithoutAttachmentsStillWorks() {
        var r = new ImportedObjectFileRecord("a.geojson", "i.import_1.geojson", "/a", new byte[3], "s", 1, T, "u");
        assertEquals("", r.context);
        assertTrue(r.attachments().isEmpty());
        assertEquals(1, r.companions().size());
    }

    @Test
    void attachmentNameKeepsTheImportAndIsASafePathSegment() {
        assertEquals("img.import_1.gen_2.my_train_v2_.py",
            ImportAttachment.companionFilename("img.import_1.geojson", 2, "my train(v2).py"));
        assertEquals("img.import_3.gen_1.noext",
            ImportAttachment.companionFilename("img.import_3", 1, "noext"));
        assertTrue(!ImportAttachment.companionFilename("a.import_1.geojson", 1, "../../etc/passwd").contains("/"));
    }
}
