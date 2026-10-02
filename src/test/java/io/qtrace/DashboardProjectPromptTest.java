package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Recent QuPath projects offered by the Dashboard's "open a project" prompt. */
class DashboardProjectPromptTest {

    @Test
    void namesAProjectAfterItsFolder_andDropsWhatNoLongerExists(@TempDir Path tmp) throws Exception {
        Path kept = Files.createDirectories(tmp.resolve("TMA_study")).resolve("project.qpproj");
        Files.writeString(kept, "{}");
        URI gone = tmp.resolve("deleted").resolve("project.qpproj").toUri();

        List<DashboardProjectPrompt.Recent> recents =
            DashboardProjectPrompt.recents(List.of(gone, kept.toUri(), URI.create("https://example.org/p.qpproj")), 6);

        assertEquals(1, recents.size());
        assertEquals("TMA_study", recents.get(0).name());
        assertEquals(kept.getParent().toString(), recents.get(0).folder());
        assertEquals(kept.toUri(), recents.get(0).uri());
    }

    @Test
    void keepsTheMostRecentFirstAndStopsAtTheLimit(@TempDir Path tmp) throws Exception {
        java.util.ArrayList<URI> uris = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Path p = Files.createDirectories(tmp.resolve("p" + i)).resolve("project.qpproj");
            Files.writeString(p, "{}");
            uris.add(p.toUri());
        }
        List<DashboardProjectPrompt.Recent> recents = DashboardProjectPrompt.recents(uris, 3);
        assertEquals(List.of("p0", "p1", "p2"), recents.stream().map(DashboardProjectPrompt.Recent::name).toList());
    }
}
