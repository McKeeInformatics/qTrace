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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What the modules share to talk to qtrace.ca and to find their way around a qTrace
 * installation: the certificate's token, an authenticated GET, the folder of the modules,
 * version comparison, and waiting for QuPath's modal dialogs before showing a window.
 *
 * <p>Core downloads and installs nothing: deciding which modules are on this workstation,
 * and updating them, is the provisioning module's work (its {@code ModuleInstaller},
 * loader.md § 7). The name is kept because every module calls this class.
 */
public final class QTraceUpdater {

    private QTraceUpdater() {}

    private static final Logger log = LoggerFactory.getLogger(QTraceUpdater.class);
    private static final String TAG = "[qtrace-update] ";

    private static final java.util.Set<Path> scheduledForDeletion = new java.util.HashSet<>();

    /** True when Core runs from a .qtjar, i.e. was loaded by the qTrace loader. */
    public static boolean loaderMode() {
        try {
            var src = QTraceUpdater.class.getProtectionDomain().getCodeSource();
            return src != null && ModuleUpdates.isLoaderMode(Path.of(src.getLocation().toURI()));
        } catch (Exception e) {
            return false;
        }
    }

    /** Reads the .qtlicense JWT from config (bare JWT or {"jwt":...} envelope). Public: every module authenticates with it. */
    public static String licenseJwt() {
        try {
            String path = QTraceConfig.get().getLicensePath();
            if (path == null || path.isBlank()) return null;
            String content = Files.readString(Path.of(path)).strip();
            if (content.startsWith("{")) {
                JsonObject env = JsonParser.parseString(content).getAsJsonObject();
                return env.has("jwt") ? env.get("jwt").getAsString() : null;
            }
            return content;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Extensions dir + JAR cleanup ────────────────────────────────────────────

    /**
     * Resolves the QuPath extensions directory.
     * Primary: the directory holding the currently-loaded JAR (via CodeSource) —
     * classloader-independent and exactly where new JARs must go.
     * Fallbacks: QuPath's UserDirectoryManager (reflection, 0.7+), then a default path.
     */
    public static Path extensionsDir(Class<?> anchor) {
        try {
            var src = anchor.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                // .jar (direct install) or .qtjar (extensions/qtrace/ under the loader)
                Path dir = ModuleUpdates.folderOf(Path.of(src.getLocation().toURI()));
                if (dir != null) return dir;
            }
        } catch (Exception ignored) {}
        try {
            Class<?> udm = Class.forName("qupath.lib.gui.UserDirectoryManager");
            Object instance = udm.getMethod("getInstance").invoke(null);
            Object path = udm.getMethod("getExtensionsPath").invoke(instance);
            if (path instanceof Path p && p != null) return p;
        } catch (Exception ignored) {}
        return Path.of(System.getProperty("user.home"), "QuPath", "v0.7", "extensions");
    }

    /**
     * Removes versioned JARs for {@code module} older than the highest present, plus the
     * legacy unversioned {@code qtrace-<module>.jar} (pre-dates the auto-updater's versioned
     * filenames) whenever a versioned JAR exists — leaving both on the classpath means two
     * definitions of the same plugin class, and QuPath's classloader can pick either one,
     * silently reviving an old version even after a successful update (best-effort).
     */
    public static void reapOldJars(Class<?> anchor, String module) {
        Path dir = extensionsDir(anchor);
        JarInstaller.ReapResult r = JarInstaller.reap(dir, module);
        log.info(TAG + "reap {}: extensions dir = {}, keeping version {}", module, dir, r.kept());
        r.deleted().forEach(p -> log.info(TAG + "reap {}: deleted {}", module, p));
        r.failed().forEach((p, err) -> log.warn(TAG + "reap {}: could NOT delete {} ({})", module, p, err));

        // Windows keeps loaded JARs locked: delete them once this QuPath process has exited,
        // otherwise the old JAR sorts first at the next start and is loaded (and locked) again.
        List<Path> pending = new ArrayList<>();
        synchronized (scheduledForDeletion) {
            for (Path p : r.failed().keySet())
                if (p.getFileName().toString().endsWith(".jar") && scheduledForDeletion.add(p)) pending.add(p);
        }
        if (pending.isEmpty()) return;
        try {
            JarInstaller.scheduleDeleteAfterExit(ProcessHandle.current().pid(), pending);
            log.info(TAG + "reap {}: will delete {} once QuPath exits", module, pending);
        } catch (Exception e) {
            log.warn(TAG + "reap {}: could not schedule deletion of {} ({})", module, pending, e.toString());
        }
    }

    /** Compares dotted numeric versions. >0 if a>b, 0 equal, <0 a<b. */
    public static int compareSemver(String a, String b) {
        return JarInstaller.compareSemver(a, b);
    }

    public static byte[] httpGetBytes(String url, String bearer) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NORMAL, not ALWAYS: never follow an https → http downgrade with a bearer attached.
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(url)).timeout(Duration.ofSeconds(120)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
            // Authenticated calls go to qtrace.ca: say which qTrace is asking (BackOffice › user).
            b.header("X-QTrace-Version", QTraceController.VERSION);
        }
        HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        return resp.body();
    }

    /**
     * Runs {@code action} on the FX thread once no nested event loop is running, i.e. no
     * modal dialog is open (typically QuPath's own Welcome window at startup). QuPath
     * silently discards a close request issued from a nested loop ("Close request from
     * nested loop - will be discarded"), so both the update prompt and the post-install
     * quit wait for it. Public: the provisioning and welcome modules wait the same way.
     */
    public static void whenNoModalOpen(String what, Runnable action) {
        whenNoModalOpen(what, action, true);
    }

    private static void whenNoModalOpen(String what, Runnable action, boolean first) {
        if (!Platform.isNestedLoopRunning()) {
            action.run();
            return;
        }
        if (first) log.info(TAG + "{} deferred until the open modal dialog closes", what);
        var retry = new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
        // Re-check outside the animation pulse: showAndWait() is forbidden during
        // animation processing (IllegalStateException).
        retry.setOnFinished(e -> Platform.runLater(() -> whenNoModalOpen(what, action, false)));
        retry.play();
    }
}
