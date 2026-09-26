package io.qtrace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GitBridgeTest {

    private static RevCommit head(Path repo) throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            return git.log().setMaxCount(1).call().iterator().next();
        }
    }

    @Test
    void commit_authorAndCommitterAreTheContributor(@TempDir Path repo) throws Exception {
        Path file = repo.resolve("classifiers").resolve("tumor.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{}");

        String hash = new GitBridge(repo).commit(file, "QTrace classifier: tumor", CommitIdentity.of(null, "L. Nguyen"));

        RevCommit c = head(repo);
        assertEquals(hash, c.abbreviate(7).name());
        assertEquals("L. Nguyen", c.getAuthorIdent().getName());
        assertEquals("L. Nguyen", c.getCommitterIdent().getName());
    }

    @Test
    void commit_neverUsesTheLegacyAstraebioIdentity(@TempDir Path repo) throws Exception {
        Path file = repo.resolve("a.json");
        Files.writeString(file, "{}");

        new GitBridge(repo).commit(file, "msg", CommitIdentity.of(null, null));

        RevCommit c = head(repo);
        assertFalse(c.getAuthorIdent().getEmailAddress().contains("astraebio"));
        assertFalse(c.getCommitterIdent().getEmailAddress().contains("astraebio"));
        assertEquals("qTrace", c.getAuthorIdent().getName());
    }
}
