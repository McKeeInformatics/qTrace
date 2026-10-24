package io.qtrace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where an image's .qtrace lives: one rule, for the writer and for every reader. */
class QTraceExporterFileTest {

    private static final Path DIR = Path.of("/data/trace");

    @Test
    void anImageNameKeepsItsLettersDigitsDotsDashesAndUnderscores() {
        assertEquals(DIR.resolve("Slide_042-a.svs.qtrace"), QTraceExporter.fileFor(DIR, "Slide_042-a.svs"));
    }

    @Test
    void anythingElseBecomesAnUnderscore() {
        assertEquals(DIR.resolve("Slide_1_r_gion__2_.tif.qtrace"), QTraceExporter.fileFor(DIR, "Slide 1 région (2).tif"));
        assertEquals(DIR.resolve("a_b.qtrace"), QTraceExporter.fileFor(DIR, "a/b"));
    }
}
