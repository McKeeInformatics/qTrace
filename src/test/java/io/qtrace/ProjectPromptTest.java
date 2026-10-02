package io.qtrace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Recent QuPath projects offered by the Dashboard's "open a project" prompt. */
class ProjectPromptTest {

    @Test
    void namesAProjectAfterItsFolder_andDropsWhatNoLongerExists(@TempDir Path tmp) throws Exception {
        Path kept = Files.createDirectories(tmp.resolve("TMA_study")).resolve("project.qpproj");
        Files.writeString(kept, "{}");
        URI gone = tmp.resolve("deleted").resolve("project.qpproj").toUri();

        List<ProjectPrompt.Recent> recents =
            ProjectPrompt.recents(List.of(gone, kept.toUri(), URI.create("https://example.org/p.qpproj")), 6);

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
        List<ProjectPrompt.Recent> recents = ProjectPrompt.recents(uris, 3);
        assertEquals(List.of("p0", "p1", "p2"), recents.stream().map(ProjectPrompt.Recent::name).toList());
    }

    @Test
    void resumesTheRememberedProject_orTheMostRecentOneWhenItIsGone(@TempDir Path tmp) throws Exception {
        Path last = Files.createDirectories(tmp.resolve("last")).resolve("project.qpproj");
        Files.writeString(last, "{}");
        Path other = Files.createDirectories(tmp.resolve("other")).resolve("project.qpproj");
        Files.writeString(other, "{}");
        List<URI> recent = List.of(other.toUri(), last.toUri());

        assertEquals(last.toUri(), ProjectPrompt.projectToResume(last.toUri().toString(), recent));
        // Remembered project deleted, or nothing remembered yet: QuPath's most recent one.
        assertEquals(other.toUri(), ProjectPrompt.projectToResume(tmp.resolve("gone/project.qpproj").toUri().toString(), recent));
        assertEquals(other.toUri(), ProjectPrompt.projectToResume(null, recent));
        assertEquals(null, ProjectPrompt.projectToResume("not a uri", List.of()));
    }
}
