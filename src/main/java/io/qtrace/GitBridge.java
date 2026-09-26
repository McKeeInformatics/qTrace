/*
 * qTrace — QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package io.qtrace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Local Git history for classifier files.
 *
 * Opens (or initialises) a local Git repository at the classifier output directory and commits
 * one file per call. Today only pixel-classifier JSON goes through here (loaded or trained);
 * the short hash ends up in the .qtrace as {@code classifiers[].git_hash}, so a record points at
 * the exact classifier version it used. Commits are not signed.
 *
 * Author and committer = the user's {@link CommitIdentity}.
 * Uses JGit (bundled in the fat JAR — no external Git installation required).
 */
public class GitBridge {

    private final Path repoDir;

    public GitBridge(Path repoDir) {
        this.repoDir = repoDir;
    }

    /**
     * Stage + commit one file.
     * The repository is initialised automatically if it doesn't exist yet.
     *
     * @param file        absolute path of the file to commit (must be inside repoDir)
     * @param message     full commit message
     * @param identity    author and committer (see {@link CommitIdentity})
     * @return 7-character abbreviated SHA-1 of the new commit
     */
    public String commit(Path file, String message, CommitIdentity identity)
            throws IOException, GitAPIException {
        File dir = repoDir.toFile();

        Git git;
        try {
            git = Git.open(dir);
        } catch (Exception e) {
            // First run: initialise a fresh repo
            git = Git.init().setDirectory(dir).call();
        }

        try {
            String relative = repoDir.relativize(file).toString();
            git.add().addFilepattern(relative).call();

            PersonIdent who = identity.person();
            RevCommit commit = git.commit()
                .setMessage(identity.message(message))
                .setAuthor(who)
                .setCommitter(who)
                .call();

            return commit.abbreviate(7).name();
        } finally {
            git.close();
        }
    }
}
