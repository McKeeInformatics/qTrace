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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Writes the files that hold a bearer secret (the identity certificate, an account token, the
 * signing key, the proxy password) so that only their owner can read them: whoever copies a {@code .qtlicense} can
 * upload in the holder's name for a year.
 *
 * <ul>
 *   <li>POSIX (Linux, macOS): the file is <em>created</em> 0600 and its folder 0700, never written
 *       then chmod-ed, so there is no moment when it is readable.</li>
 *   <li>Windows: an ACL (access control list) granting the current user alone, set on the folder
 *       (inherited by what is created in it) and on the file.</li>
 *   <li>The content goes to a private temporary file next to the target, then an atomic move: a
 *       crash leaves the old file or the new one, never half of one.</li>
 * </ul>
 *
 * {@link #tighten} brings a file written by an earlier version (0644) to the same state.
 */
public final class SecretFiles {

    private static final Logger log = LoggerFactory.getLogger(SecretFiles.class);

    /** The files of {@code ~/.qTrace} that carry a secret, with the backup copy of each. */
    public static final List<String> NAMES = List.of(
        "qtrace.qtlicense", "qtrace.qtlicense.bak",
        "qtrace.qtaccount", "qtrace.qtaccount.bak",
        "qtrace-signing.key",
        "qtrace-proxy.secret"); // the proxy password of Settings › Network (io.qtrace.net)

    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_MODE  = PosixFilePermissions.fromString("rwx------");

    private SecretFiles() {}

    private static boolean posix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static boolean acl(Path p) {
        return !posix(p) && p.getFileSystem().supportedFileAttributeViews().contains("acl");
    }

    /** Writes {@code content} (UTF-8) to {@code file}: private from its first byte, replaced atomically. */
    public static void write(Path file, String content) throws IOException {
        write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path file, byte[] content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        ensurePrivateDir(dir);
        Path tmp = createPrivateFile(dir, file.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Copies {@code from} to {@code to} with the same guarantees (a backup of a secret is a secret). */
    public static void copy(Path from, Path to) throws IOException {
        write(to, Files.readAllBytes(from));
    }

    /** Creates {@code dir} if needed, private to the owner. An existing folder is tightened. */
    public static void ensurePrivateDir(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            restrict(dir);
        } else if (posix(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE));
            restrict(dir); // createDirectories applies the umask; and parents it made
        } else {
            Files.createDirectories(dir);
            restrict(dir);
        }
    }

    private static Path createPrivateFile(Path dir, String prefix, String suffix) throws IOException {
        if (posix(dir)) {
            FileAttribute<Set<PosixFilePermission>> mode = PosixFilePermissions.asFileAttribute(FILE_MODE);
            return Files.createTempFile(dir, prefix, suffix, mode);
        }
        Path tmp = Files.createTempFile(dir, prefix, suffix);
        restrict(tmp);
        return tmp;
    }

    /**
     * Brings an existing file or folder to owner-only. Returns true when it was more open than that
     * (and was changed), false when it was already private, missing, or the system cannot say.
     */
    public static boolean restrict(Path p) {
        try {
            if (!Files.exists(p)) return false;
            if (posix(p)) {
                Set<PosixFilePermission> want = Files.isDirectory(p) ? DIR_MODE : FILE_MODE;
                Set<PosixFilePermission> have = Files.getPosixFilePermissions(p);
                if (want.equals(have)) return false;
                boolean tooOpen = have.stream().anyMatch(x -> !want.contains(x));
                Files.setPosixFilePermissions(p, want);
                return tooOpen;
            }
            if (acl(p)) return restrictAcl(p);
        } catch (Exception e) {
            log.warn("Could not restrict {}: {}", p, e.toString());
        }
        return false;
    }

    private static boolean restrictAcl(Path p) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(p, AclFileAttributeView.class);
        if (view == null) return false;
        UserPrincipal me = FileSystems.getDefault().getUserPrincipalLookupService()
            .lookupPrincipalByName(System.getProperty("user.name"));
        Set<AclEntryFlag> flags = Files.isDirectory(p)
            ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
            : EnumSet.noneOf(AclEntryFlag.class);
        AclEntry only = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW).setPrincipal(me)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build();
        List<AclEntry> have = view.getAcl();
        if (have.size() == 1 && have.get(0).principal().equals(me)) return false;
        view.setAcl(List.of(only));
        return true;
    }

    /**
     * Start-up migration: tightens {@code dir} and the known secret files in it (files written
     * before this existed were 0644). Logged once, only when something was actually more open.
     */
    public static void tightenAll(Path dir) {
        try {
            if (!Files.isDirectory(dir)) return;
            StringBuilder changed = new StringBuilder();
            if (restrict(dir)) changed.append(dir).append(' ');
            for (String name : NAMES) {
                Path f = dir.resolve(name);
                if (restrict(f)) changed.append(name).append(' ');
            }
            if (changed.length() > 0) log.info("Secret files restricted to their owner: {}", changed.toString().strip());
        } catch (Exception e) {
            log.warn("Could not restrict {}: {}", dir, e.toString());
        }
    }
}
