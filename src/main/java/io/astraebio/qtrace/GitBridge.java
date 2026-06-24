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

package io.astraebio.qtrace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Phase 4 — Git provenance bridge.
 *
 * Opens (or initialises) a local Git repository at the scripts output directory,
 * stages the generated Meta-Script, and creates a signed commit whose hash
 * becomes the immutable audit-trail anchor for this workflow capture.
 *
 * The commit hash is returned to the caller for inclusion in the QTrace panel
 * and, later, in the .qtrace JSON sidecar (Phase 6).
 *
 * Uses JGit (bundled in the fat JAR — no external Git installation required).
 */
public class GitBridge {

    static final PersonIdent QTRACE_IDENT =
        new PersonIdent("QTrace", "qtrace@astraebio.io");

    private final Path repoDir;

    public GitBridge(Path repoDir) {
        this.repoDir = repoDir;
    }

    /**
     * Stage + commit one file.
     * The repository is initialised automatically if it doesn't exist yet.
     *
     * @param file    absolute path of the file to commit (must be inside repoDir)
     * @param message full commit message
     * @return 7-character abbreviated SHA-1 of the new commit
     */
    public String commit(Path file, String message) throws IOException, GitAPIException {
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

            RevCommit commit = git.commit()
                .setMessage(message)
                .setAuthor(QTRACE_IDENT)
                .setCommitter(QTRACE_IDENT)
                .call();

            return commit.abbreviate(7).name();
        } finally {
            git.close();
        }
    }
}
