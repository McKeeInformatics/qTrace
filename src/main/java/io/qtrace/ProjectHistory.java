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

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Local Git history of a QuPath project, as far as its provenance goes.
 *
 * The repository is the one that already holds the project (a lab that keeps its projects under
 * Git gets qTrace commits in its own history), or a new one at the project root. Only what qTrace
 * tracks is committed — {@code project.qpproj}, {@code scripts/}, {@code classifiers/} and the
 * {@code qTrace/} folder — never the {@code data/} folder (.qpdata) nor temporary files. Commits
 * are restricted to those paths, so anything the lab has staged for itself stays staged.
 *
 * Author and committer = the user's {@link CommitIdentity}. Commits are not signed: this is an audit trail,
 * integrity proof comes from the signed stamp and its timestamp.
 */
public final class ProjectHistory {

    /** Top-level entries of a QuPath project that qTrace versions. */
    static final List<String> TRACKED_ROOTS =
        List.of("project.qpproj", "scripts", "classifiers", QTraceConfig.PROJECT_SUBDIR);

    /** Where the classifier-only repository of earlier versions lived, and what it is renamed to. */
    static final String LEGACY_REPO = QTraceConfig.PROJECT_SUBDIR + "/" + QTraceConfig.GITTRACK_SUBDIR + "/.git";
    static final String LEGACY_SUFFIX = ".git-legacy";

    private final Path projectDir;

    public ProjectHistory(Path projectDir) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
    }

    /**
     * Commits every tracked file that changed since the last commit.
     *
     * @return 7-character abbreviated hash, or null when nothing tracked changed
     */
    public String commitTracked(String message, CommitIdentity identity) throws IOException, GitAPIException {
        retireLegacyRepo();
        try (Git git = openOrInit()) {
            String prefix = prefix(git);
            StatusCommand st = git.status();
            for (String root : TRACKED_ROOTS) st.addPath(prefix + root);
            Status s = st.call();

            Set<String> changed = new TreeSet<>();
            Stream.of(s.getUntracked(), s.getModified(), s.getAdded(), s.getChanged())
                  .flatMap(Set::stream)
                  .filter(p -> p.startsWith(prefix) && isTracked(p.substring(prefix.length())))
                  .forEach(changed::add);
            if (changed.isEmpty()) return null;

            AddCommand add = git.add();
            changed.forEach(add::addFilepattern);
            add.call();

            PersonIdent who = identity.person();
            CommitCommand commit = git.commit().setMessage(identity.message(message)).setAuthor(who).setCommitter(who);
            changed.forEach(commit::setOnly);   // leave whatever else is staged alone
            return commit.call().abbreviate(7).name();
        }
    }

    /** Last commit that touched {@code file}, or null when it has no history yet. */
    public String lastCommitTouching(Path file) throws IOException {
        File gitDir = findEnclosingGitDir();
        if (gitDir == null) return null;
        try (Git git = Git.open(gitDir)) {
            if (git.getRepository().resolve("HEAD") == null) return null;
            Path workTree = git.getRepository().getWorkTree().toPath().toRealPath();
            Path target = file.toAbsolutePath().normalize();
            if (Files.exists(target)) target = target.toRealPath();
            if (!target.startsWith(workTree)) return null;
            Iterator<RevCommit> it = git.log().addPath(slashes(workTree.relativize(target))).setMaxCount(1).call().iterator();
            return it.hasNext() ? it.next().abbreviate(7).name() : null;
        } catch (GitAPIException e) {
            return null;
        }
    }

    /**
     * Commits one file qTrace just wrote: in the open project's history when the file lives in the
     * project (Project Folder mode), otherwise in a stand-alone repository at {@code fallbackRepo}.
     *
     * @return abbreviated hash of the commit that now holds the file
     */
    static String commitFile(Path file, Path fallbackRepo, String message, CommitIdentity identity)
            throws IOException, GitAPIException {
        Path project = QTraceConfig.currentProjectDir();
        if (project != null && file.toAbsolutePath().normalize().startsWith(project.toAbsolutePath().normalize())) {
            ProjectHistory h = new ProjectHistory(project);
            String hash = h.commitTracked(message, identity);
            return hash != null ? hash : h.lastCommitTouching(file);   // unchanged: already recorded
        }
        return new GitBridge(fallbackRepo).commit(file, message, identity);
    }

    /** True for a project-relative path qTrace versions (under a tracked root, not data or temp). */
    static boolean isTracked(String rel) {
        String p = rel.replace('\\', '/');
        boolean underRoot = TRACKED_ROOTS.stream().anyMatch(r -> p.equals(r) || p.startsWith(r + "/"));
        if (!underRoot) return false;
        if (p.contains("/.git/") || p.contains("/" + LEGACY_SUFFIX + "/")) return false;
        String name = p.substring(p.lastIndexOf('/') + 1);
        return !(name.endsWith(".qpdata") || name.endsWith(".tmp") || name.endsWith(".part")
                 || name.equals(".report-input.json"));
    }

    /** The old classifier-only repository would be an embedded repo: move it out of the way, keep it. */
    void retireLegacyRepo() throws IOException {
        Path legacy = projectDir.resolve(LEGACY_REPO);
        if (!Files.isDirectory(legacy)) return;
        Path target = legacy.resolveSibling(LEGACY_SUFFIX);
        if (Files.exists(target)) target = legacy.resolveSibling(LEGACY_SUFFIX + "-" + System.currentTimeMillis());
        Files.move(legacy, target);
    }

    private Git openOrInit() throws IOException, GitAPIException {
        File gitDir = findEnclosingGitDir();
        if (gitDir != null) return Git.open(gitDir);
        return Git.init().setDirectory(projectDir.toFile()).call();
    }

    /**
     * The repository that already holds the project, if any. A repository rooted at the user's
     * home folder (dotfiles) is not a project history and is ignored.
     */
    private File findEnclosingGitDir() throws IOException {
        FileRepositoryBuilder b = new FileRepositoryBuilder().findGitDir(projectDir.toFile());
        File gitDir = b.getGitDir();
        if (gitDir == null) return null;
        Path workTree = gitDir.toPath().toAbsolutePath().getParent();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        if (workTree != null && workTree.normalize().equals(home.normalize())) return null;
        return gitDir;
    }

    /** Project path relative to the work tree, with a trailing slash ("" at the root). */
    private String prefix(Git git) throws IOException {
        Path workTree = git.getRepository().getWorkTree().toPath().toRealPath();
        Path rel = workTree.relativize(projectDir.toRealPath());
        String s = slashes(rel);
        return s.isEmpty() ? "" : s + "/";
    }

    private static String slashes(Path p) {
        return p.toString().replace(File.separatorChar, '/');
    }
}
