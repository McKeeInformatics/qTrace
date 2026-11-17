package io.qtrace;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qupath.lib.images.ImageData;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;

/** Full reset: nothing of the past comes back when the image is opened again. */
class FullResetTest {

    private static ImageData<BufferedImage> imageWithHistory() {
        ImageData<BufferedImage> data = new ImageData<>(null);
        data.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Old step", "print 'old'"));
        return data;
    }

    @Test
    void plainResetLetsTheImageHistoryComeBackOnReattach() {
        ImageData<BufferedImage> data = imageWithHistory();
        ActionLogger logger = new ActionLogger(null, null);
        logger.attach(data);
        logger.resetCapture();
        logger.attach(data);
        assertEquals(1, logger.getCapturedSteps().size(), "documents the bug a plain Reset leaves");
    }

    @Test
    void fullResetLeavesNothingToReimport() {
        ImageData<BufferedImage> data = imageWithHistory();
        ActionLogger logger = new ActionLogger(null, null);
        logger.attach(data);
        logger.fullReset();
        assertTrue(logger.getCapturedSteps().isEmpty());
        assertEquals(0, data.getHistoryWorkflow().size());
        logger.attach(data);
        assertTrue(logger.getCapturedSteps().isEmpty(), "the past must not come back on reopening");
        assertFalse(logger.hasSteps());
    }

    @Test
    void fullResetStillRecordsWhatHappensAfterwards() {
        ImageData<BufferedImage> data = imageWithHistory();
        ActionLogger logger = new ActionLogger(null, null);
        logger.attach(data);
        logger.fullReset();
        data.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("New step", "print 'new'"));
        assertEquals(1, logger.getCapturedSteps().size());
    }

    @Test
    void archiveMovesTheQtraceAside(@TempDir Path dir) throws Exception {
        Path q = dir.resolve("slide.qtrace");
        Files.writeString(q, "{\"sessions\":[]}");
        Path archived = QTraceArchive.archive(q).orElseThrow();
        assertFalse(Files.exists(q), "the live .qtrace is gone, the next stamp starts a fresh chain");
        assertEquals("{\"sessions\":[]}", Files.readString(archived));
        assertTrue(archived.getFileName().toString().startsWith("slide.qtrace.reset-"));
        assertFalse(archived.getFileName().toString().endsWith(".qtrace"),
            "the archive must not be picked up as a record");
    }

    @Test
    void archiveTwiceNeverOverwrites(@TempDir Path dir) throws Exception {
        Path q = dir.resolve("slide.qtrace");
        Files.writeString(q, "a");
        Path first = QTraceArchive.archive(q).orElseThrow();
        Files.writeString(q, "b");
        Path second = QTraceArchive.archive(q).orElseThrow();
        assertNotEquals(first, second);
        assertEquals("a", Files.readString(first));
        assertEquals("b", Files.readString(second));
    }

    @Test
    void archiveOfNothingIsEmpty(@TempDir Path dir) throws Exception {
        assertTrue(QTraceArchive.archive(dir.resolve("none.qtrace")).isEmpty());
    }

    @Test
    void archiveTakesTheCompanionFilesAlong(@TempDir Path dir) throws Exception {
        Path q = dir.resolve("slide.qtrace");
        Files.writeString(q, "{}");
        Files.createDirectories(dir.resolve("slide.qtrace.files"));
        Files.writeString(dir.resolve("slide.qtrace.files/c.json"), "c");
        Path archived = QTraceArchive.archive(q).orElseThrow();
        assertFalse(Files.exists(dir.resolve("slide.qtrace.files")));
        assertEquals("c", Files.readString(archived.resolveSibling(archived.getFileName() + ".files/c.json")));
    }

    @Test
    void onlyTheEverythingLevelSetsTheRecordAsideAndOnlyStampedOnesAskForAnAck() {
        assertFalse(ResetLevel.CAPTURE.emptiesImageHistory());
        assertTrue(ResetLevel.IMAGE.emptiesImageHistory());
        assertFalse(ResetLevel.IMAGE.setsRecordAside());
        assertTrue(ResetLevel.EVERYTHING.emptiesImageHistory());
        assertTrue(ResetLevel.EVERYTHING.setsRecordAside());
        assertTrue(ResetDialog.needsAcknowledgement(ResetLevel.EVERYTHING, 1));
        assertFalse(ResetDialog.needsAcknowledgement(ResetLevel.EVERYTHING, 0));
        assertFalse(ResetDialog.needsAcknowledgement(ResetLevel.IMAGE, 3));
    }
}
