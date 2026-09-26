package io.qtrace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectHistoryTest {

    /** A QuPath project as qTrace sees it in Project Folder mode. */
    private static Path qupathProject(Path root) throws Exception {
        write(root.resolve("project.qpproj"), "{\"images\":[]}");
        write(root.resolve("scripts/ki67.groovy"), "runPlugin('x', '{}')");
        write(root.resolve("classifiers/pixel_classifiers/tumor.json"), "{}");
        write(root.resolve("data/1/data.qpdata"), "binary");
        write(root.resolve("data/1/server.json"), "{}");
        write(root.resolve("qTrace/trace/TMA_Core_07.qtrace"), "{\"sessions\":[]}");
        write(root.resolve("qTrace/trace/TMA_Core_07.thumbnail.jpg"), "jpg");
        write(root.resolve("qTrace/trace/case_A/chain.jsonl"), "{}");
        write(root.resolve("qTrace/trace/export.tmp"), "partial");
        write(root.resolve("qTrace/geoJson/tumor_annotations.geojson"), "{}");
        return root;
    }

    private static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s);
    }

    private static Set<String> filesIn(Path workTree, String rev) throws Exception {
        try (Git git = Git.open(workTree.toFile())) {
            Repository repo = git.getRepository();
            RevCommit c = git.log().add(repo.resolve(rev)).setMaxCount(1).call().iterator().next();
            Set<String> out = new HashSet<>();
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(c.getTree());
                tw.setRecursive(true);
                while (tw.next()) out.add(tw.getPathString());
            }
            return out;
        }
    }

    @Test
    void firstCommit_createsTheRepoAtTheProjectRoot_andVersionsOnlyWhatQtraceTracks(@TempDir Path dir) throws Exception {
        Path project = qupathProject(dir);

        String hash = new ProjectHistory(project).commitTracked("qTrace: session 1", "A. Moreau");

        assertNotNull(hash);
        assertTrue(Files.isDirectory(project.resolve(".git")));
        Set<String> files = filesIn(project, "HEAD");
        assertTrue(files.contains("project.qpproj"));
        assertTrue(files.contains("scripts/ki67.groovy"));
        assertTrue(files.contains("classifiers/pixel_classifiers/tumor.json"));
        assertTrue(files.contains("qTrace/trace/TMA_Core_07.qtrace"));
        assertTrue(files.contains("qTrace/trace/TMA_Core_07.thumbnail.jpg"));
        assertTrue(files.contains("qTrace/trace/case_A/chain.jsonl"));
        assertTrue(files.contains("qTrace/geoJson/tumor_annotations.geojson"));
        assertFalse(files.contains("data/1/data.qpdata"), "qpdata is never versioned");
        assertFalse(files.contains("data/1/server.json"), "the QuPath data folder is never versioned");
        assertFalse(files.contains("qTrace/trace/export.tmp"), "temporary files are never versioned");
    }

    @Test
    void commit_authorIsTheContributor(@TempDir Path dir) throws Exception {
        Path project = qupathProject(dir);
        new ProjectHistory(project).commitTracked("qTrace: session 1", "A. Moreau");
        try (Git git = Git.open(project.toFile())) {
            RevCommit c = git.log().setMaxCount(1).call().iterator().next();
            assertEquals("A. Moreau", c.getAuthorIdent().getName());
            assertEquals("noreply@qtrace.ca", c.getCommitterIdent().getEmailAddress());
        }
    }

    @Test
    void nothingChanged_noNewCommit(@TempDir Path dir) throws Exception {
        Path project = qupathProject(dir);
        ProjectHistory h = new ProjectHistory(project);
        assertNotNull(h.commitTracked("first", "A. Moreau"));
        assertNull(h.commitTracked("second", "A. Moreau"));
    }

    @Test
    void enclosingLabRepo_isUsed_andTheLabsOwnStagedWorkIsLeftAlone(@TempDir Path lab) throws Exception {
        write(lab.resolve("README.md"), "lab");
        write(lab.resolve("notes/plan.md"), "draft");
        try (Git git = Git.init().setDirectory(lab.toFile()).call()) {
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("lab init").setAuthor("Lab", "lab@x").setCommitter("Lab", "lab@x").call();
            git.add().addFilepattern("notes/plan.md").call();           // staged, not committed by the lab
        }
        Path project = qupathProject(lab.resolve("projects/glioma"));

        new ProjectHistory(project).commitTracked("qTrace: session 1", "A. Moreau");

        assertFalse(Files.exists(project.resolve(".git")), "no nested repo when the project already sits in one");
        Set<String> files = filesIn(lab, "HEAD");
        assertTrue(files.contains("projects/glioma/qTrace/trace/TMA_Core_07.qtrace"));
        assertFalse(files.contains("notes/plan.md"), "the lab's staged file is not swept into qTrace's commit");
        try (Git git = Git.open(lab.toFile())) {
            assertTrue(git.status().call().getAdded().contains("notes/plan.md"), "still staged for the lab");
        }
    }

    @Test
    void legacyGitTrackRepo_isRetired_notDeleted(@TempDir Path dir) throws Exception {
        Path project = qupathProject(dir);
        Path legacy = project.resolve("qTrace/gitTrack");
        write(legacy.resolve("classifiers/tumor.json"), "{}");
        try (Git git = Git.init().setDirectory(legacy.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("old").setAuthor("QTrace", "qtrace@astraebio.io")
               .setCommitter("QTrace", "qtrace@astraebio.io").call();
        }

        new ProjectHistory(project).commitTracked("qTrace: session 1", "A. Moreau");

        assertFalse(Files.exists(legacy.resolve(".git")));
        assertTrue(Files.isDirectory(legacy.resolve(".git-legacy")), "old history kept on disk, out of the way");
        Set<String> files = filesIn(project, "HEAD");
        assertTrue(files.contains("qTrace/gitTrack/classifiers/tumor.json"));
        assertFalse(files.stream().anyMatch(f -> f.contains(".git-legacy")));
    }

    @Test
    void lastCommitTouching_followsTheQtraceFile(@TempDir Path dir) throws Exception {
        Path project = qupathProject(dir);
        Path qtrace = project.resolve("qTrace/trace/TMA_Core_07.qtrace");
        ProjectHistory h = new ProjectHistory(project);

        assertNull(h.lastCommitTouching(qtrace), "no history yet");
        String first = h.commitTracked("session 1", "A. Moreau");
        assertEquals(first, h.lastCommitTouching(qtrace));

        write(project.resolve("scripts/other.groovy"), "x");
        h.commitTracked("script only", "A. Moreau");
        assertEquals(first, h.lastCommitTouching(qtrace), "a commit that doesn't touch the file doesn't move it");

        Files.writeString(qtrace, "{\"sessions\":[{}]}");
        String third = h.commitTracked("session 2", "J. Kaur");
        assertEquals(third, h.lastCommitTouching(qtrace));
    }
}
