package io.qtrace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitIdentityTest {

    private static LicenseInfo license(String name, String email, String key, Instant expires) {
        return new LicenseInfo("user_123", name, "Institut X", email, true,
            Instant.now().minus(1, ChronoUnit.DAYS), expires, key, null, null, null);
    }

    private static final Instant NEXT_YEAR = Instant.now().plus(365, ChronoUnit.DAYS);

    @Test
    void certificate_givesCertifiedNameEmailAndKey() {
        CommitIdentity id = CommitIdentity.of(license("A. Moreau", "a.moreau@institut.org", "MCowBQYDK2VwAyEAkey", NEXT_YEAR), "ignored");
        assertEquals("A. Moreau", id.person().getName());
        assertEquals("a.moreau@institut.org", id.person().getEmailAddress());
        assertTrue(id.trailers().contains("qTrace-Validator-Key: MCowBQYDK2VwAyEAkey"));
        assertTrue(id.trailers().contains("qTrace-Certified: yes"));
    }

    @Test
    void expiredCertificate_fallsBackToTheContributor() {
        CommitIdentity id = CommitIdentity.of(license("A. Moreau", "a@x.org", "k", Instant.now().minusSeconds(60)), "L. Nguyen");
        assertEquals("L. Nguyen", id.person().getName());
        assertEquals("", id.person().getEmailAddress());
        assertEquals("", id.trailers());
    }

    @Test
    void coreOnly_contributorNameWithoutEmail() {
        CommitIdentity id = CommitIdentity.of(null, "L. Nguyen");
        assertEquals("L. Nguyen", id.person().getName());
        assertEquals("", id.person().getEmailAddress());
        assertEquals("", id.trailers());
    }

    @Test
    void nobody_lastResortIsQtrace() {
        CommitIdentity id = CommitIdentity.of(null, " ");
        assertEquals("qTrace", id.person().getName());
        assertEquals("noreply@qtrace.ca", id.person().getEmailAddress());
    }

    @Test
    void projectCommit_authorAndCommitterAreTheUser_withTrailers(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("project.qpproj"), "{}");
        CommitIdentity id = CommitIdentity.of(license("A. Moreau", "a.moreau@institut.org", "KEY123", NEXT_YEAR), "x");

        new ProjectHistory(project).commitTracked("qTrace: stamped session · a.qtrace", id);

        try (Git git = Git.open(project.toFile())) {
            RevCommit c = git.log().setMaxCount(1).call().iterator().next();
            assertEquals("a.moreau@institut.org", c.getAuthorIdent().getEmailAddress());
            assertEquals("a.moreau@institut.org", c.getCommitterIdent().getEmailAddress());
            assertEquals("A. Moreau", c.getCommitterIdent().getName());
            assertTrue(c.getFullMessage().startsWith("qTrace: stamped session · a.qtrace\n\n"));
            assertTrue(c.getFullMessage().contains("qTrace-Validator-Key: KEY123"));
            assertFalse(c.getFullMessage().contains("qtrace.ca"));
        }
    }
}
