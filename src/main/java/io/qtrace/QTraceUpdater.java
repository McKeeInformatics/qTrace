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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup update mechanism shared by Core (public GitHub) and Compliance (qtrace.ca).
 *
 * Design (decided with the maintainer):
 *  - Never silent: the user is prompted before anything is downloaded/installed.
 *  - Never hot-swap: a loaded classloader can't be replaced safely (Windows file
 *    locks), so the new JAR is written to the extensions dir and applied on the
 *    next QuPath restart. {@code QTracePluginManager} registers the highest-version
 *    plugin among those loaded, so a single restart is enough even if the old file
 *    lingers; {@link #reapOldJars} removes it best-effort.
 *  - Integrity: the downloaded JAR's SHA-256 is checked against the manifest value
 *    (when provided) before installation.
 */
public final class QTraceUpdater {

    private QTraceUpdater() {}

    private static final Logger log = LoggerFactory.getLogger(QTraceUpdater.class);
    private static final String TAG = "[qtrace-update] ";

    // One startup session may update Core AND Compliance. Each check is an open task
    // until it resolves (nothing to offer / prompt declined / download finished); the
    // single "installed — Quit QuPath Now" dialog is shown only once ALL tasks are done,
    // otherwise quitting after the first install dropped the second prompt unseen.
    private static final AtomicInteger openTasks = new AtomicInteger();
    private static final List<String> installedThisSession = new ArrayList<>();

    // Overridable for the local update simulator (tools/update-sim/):
    //   -Dqtrace.update.server=http://127.0.0.1:8765   (version + compliance download)
    //   -Dqtrace.update.github=http://127.0.0.1:8765/github/releases/latest
    private static final String GITHUB_LATEST = System.getProperty("qtrace.update.github",
        "https://api.github.com/repos/RomainTourte/qTrace-core/releases/latest");
    // www.qtrace.ca (not the apex) — qtrace.ca 308-redirects to www, and a
    // cross-host redirect makes java.net.http.HttpClient drop the Authorization
    // header, breaking the licensed compliance download (HTTP 401).
    private static final String SERVER = System.getProperty("qtrace.update.server",
        "https://www.qtrace.ca");
    private static final String VERSION_URL = SERVER + "/api/version";
    private static final String COMP_DOWNLOAD_URL = SERVER + "/api/download/compliance/licensed";

    private static final Pattern JAR_VERSION =
        Pattern.compile("^qtrace-(?:core|compliance)-(\\d+(?:\\.\\d+)*)\\.jar$");

    @FunctionalInterface
    public interface Downloader { byte[] download() throws Exception; }

    // ── Core check (public GitHub release) ──────────────────────────────────────

    /** Async, safe. Offers an update if the latest qTrace-core GitHub release is newer. */
    public static void checkCore(QuPathGUI qupath) {
        if (!QTraceConfig.get().isUpdateCheckEnabled()) {
            log.info(TAG + "core check skipped: update check disabled in config");
            return;
        }
        openTasks.incrementAndGet();
        CompletableFuture.runAsync(() -> {
            boolean handedOff = false;
            try {
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_LATEST))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.info(TAG + "core check: {} → HTTP {}", GITHUB_LATEST, resp.statusCode());
                    return;
                }

                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                String tag = json.has("tag_name") ? json.get("tag_name").getAsString() : null;
                if (tag == null) return;
                String remote = tag.startsWith("v") ? tag.substring(1) : tag;

                log.info(TAG + "core check: local={} remote={}", QTraceController.VERSION, remote);
                if (compareSemver(remote, QTraceController.VERSION) <= 0) return;

                String assetUrl = null;
                if (json.has("assets")) {
                    JsonArray assets = json.getAsJsonArray("assets");
                    for (var el : assets) {
                        JsonObject a = el.getAsJsonObject();
                        String name = a.has("name") ? a.get("name").getAsString() : "";
                        if (name.startsWith("qtrace-core") && name.endsWith(".jar")) {
                            assetUrl = a.get("browser_download_url").getAsString();
                            break;
                        }
                    }
                }
                if (assetUrl == null) {
                    log.warn(TAG + "core release {} has no qtrace-core*.jar asset", tag);
                    return;
                }

                final String url = assetUrl;
                Downloader dl = () -> httpGetBytes(url, null);
                handedOff = promptAndInstall(qupath, "core", QTraceController.VERSION, remote, null, dl);
            } catch (Exception e) {
                // offline / rate-limited — no dialog, but leave a trace
                log.info(TAG + "core check failed: {}", e.toString());
            } finally {
                if (!handedOff) taskDone(qupath);
            }
        });
    }

    // ── Compliance check (driven by Core, works with any Compliance JAR) ─────────

    /**
     * Async, safe. Offers a Compliance update based on the qtrace.ca manifest.
     * Driven by the Core (not the Compliance JAR) so it works even when the
     * installed Compliance JAR predates the auto-update feature — it only reads
     * the loaded plugin's reported version and the license from config.
     */
    public static void checkCompliance(QuPathGUI qupath, QTracePlugin ep) {
        if (ep == null || !QTraceConfig.get().isUpdateCheckEnabled()) {
            log.info(TAG + "compliance check skipped: plugin={} enabled={}",
                ep, QTraceConfig.get().isUpdateCheckEnabled());
            return;
        }
        final String currentVer = ep.getPluginVersion();
        openTasks.incrementAndGet();
        CompletableFuture.runAsync(() -> {
            boolean handedOff = false;
            try {
                String jwt = licenseJwt();
                if (jwt == null) { // no license → can't authenticate the download
                    log.info(TAG + "compliance check skipped: no license configured");
                    return;
                }

                byte[] mb = httpGetBytes(VERSION_URL, null);
                JsonObject manifest = JsonParser
                    .parseString(new String(mb, StandardCharsets.UTF_8)).getAsJsonObject();
                String manifestKey = "compliance";
                if (!manifest.has(manifestKey)) return;
                JsonObject ent = manifest.getAsJsonObject(manifestKey);
                String remoteVer = ent.has("version") ? ent.get("version").getAsString() : null;
                String sha256    = ent.has("sha256")  ? ent.get("sha256").getAsString()  : null;
                log.info(TAG + "compliance check: local={} remote={} sha256={}", currentVer, remoteVer, sha256);
                if (remoteVer == null) return;

                Downloader dl = () -> httpGetBytes(COMP_DOWNLOAD_URL, jwt);
                handedOff = promptAndInstall(qupath, "compliance", currentVer, remoteVer, sha256, dl);
            } catch (Exception e) {
                // offline / no license — no dialog, but leave a trace
                log.info(TAG + "compliance check failed: {}", e.toString());
            } finally {
                if (!handedOff) taskDone(qupath);
            }
        });
    }

    /** Reads the .qtlicense JWT from config (bare JWT or {"jwt":...} envelope). */
    static String licenseJwt() {
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

    // ── Shared install flow ─────────────────────────────────────────────────────

    /**
     * Prompts the user about {@code remoteVer} and, on confirmation, downloads,
     * verifies (if {@code expectedSha256} non-null) and installs the JAR.
     * Safe to call from any thread. Returns true when a prompt was scheduled: the
     * caller's open task is then closed by this flow (see {@link #taskDone}).
     */
    public static boolean promptAndInstall(QuPathGUI qupath, String module, String currentVer,
                                        String remoteVer, String expectedSha256, Downloader downloader) {
        if (remoteVer == null || compareSemver(remoteVer, currentVer) <= 0) {
            log.info(TAG + "{}: up to date (local={} remote={})", module, currentVer, remoteVer);
            return false;
        }
        if (remoteVer.equals(QTraceConfig.get().getDismissedUpdateVersion())) {
            log.info(TAG + "{}: {} was dismissed by the user", module, remoteVer);
            return false;
        }
        log.info(TAG + "{}: offering {} → {}", module, currentVer, remoteVer);

        // Shown only once no modal dialog is open (QuPath's Welcome window at startup):
        // stacked on top of it, the post-install "Quit QuPath Now" could never work.
        Platform.runLater(() -> whenNoModalOpen("update prompt", () -> {
            String label = "core".equals(module) ? "Core" : "Compliance";
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(QTraceI18n.t("update.title"));
            alert.setHeaderText(QTraceI18n.t("update.available")
                .replace("{0}", label).replace("{1}", remoteVer).replace("{2}", currentVer));
            alert.setContentText(QTraceI18n.t("update.prompt"));
            if (qupath != null && qupath.getStage() != null) alert.initOwner(qupath.getStage());

            ButtonType install = new ButtonType(QTraceI18n.t("update.install"), ButtonBar.ButtonData.OK_DONE);
            ButtonType later   = new ButtonType(QTraceI18n.t("update.later"),   ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType ignore  = new ButtonType(QTraceI18n.t("update.ignore"),  ButtonBar.ButtonData.OTHER);
            alert.getButtonTypes().setAll(install, later, ignore);

            Optional<ButtonType> result = alert.showAndWait();
            log.info(TAG + "{}: user chose {}", module, result.map(ButtonType::getText).orElse("<closed>"));
            if (result.isEmpty() || result.get() == later) { taskDone(qupath); return; }
            if (result.get() == ignore) {
                QTraceConfig.get().setDismissedUpdateVersion(remoteVer);
                QTraceConfig.get().save();
                taskDone(qupath);
                return;
            }
            // Install → background download + write
            CompletableFuture.runAsync(() ->
                downloadAndInstall(qupath, module, remoteVer, expectedSha256, downloader));
        }));
        return true;
    }

    private static void downloadAndInstall(QuPathGUI qupath, String module, String remoteVer,
                                           String expectedSha256, Downloader downloader) {
        try {
            byte[] data = downloader.download();
            if (data == null || data.length == 0) throw new Exception("empty download");
            String actual = sha256Hex(data);
            log.info(TAG + "{}: downloaded {} bytes, sha256={}", module, data.length, actual);
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                if (!actual.equalsIgnoreCase(expectedSha256))
                    throw new Exception("SHA-256 mismatch (expected " + expectedSha256 + ", got " + actual + ")");
            }

            Path dir = extensionsDir(QTraceUpdater.class);
            Path target = dir.resolve("qtrace-" + module + "-" + remoteVer + ".jar");
            Path tmp = dir.resolve("qtrace-" + module + "-" + remoteVer + ".jar.part");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info(TAG + "{}: wrote {}", module, target);

            // Remove the superseded file(s) for this module NOW rather than waiting for the
            // next startup's reapOldJars() call. Two same-named-class JARs (e.g. the bare
            // legacy qtrace-compliance.jar plus this new versioned one) sitting on the
            // extensions dir at the NEXT boot let QuPath's classloader silently keep
            // resolving whichever one it finds first — our "pick the highest getPluginVersion()"
            // logic in QTraceExtension can't out-vote that, because a classloader only ever
            // defines one Class object per fully-qualified name, so only one of the two JARs'
            // classes is ever actually visible, regardless of which ServiceLoader entry we
            // pick. Reaping right away means only the new file exists at the next restart, so
            // there is nothing left to be ambiguous about — one restart is enough.
            QTraceUpdater.reapOldJars(QTraceUpdater.class, module);

            synchronized (installedThisSession) {
                installedThisSession.add(("core".equals(module) ? "Core" : "Compliance") + " v" + remoteVer);
            }
        } catch (Exception e) {
            log.warn(TAG + "{}: install of {} failed", module, remoteVer, e);
            error(qupath, QTraceI18n.t("update.failed").replace("{0}", e.getMessage()));
        } finally {
            taskDone(qupath);
        }
    }

    /** Closes one open task; the last one shows the single post-install dialog. */
    private static void taskDone(QuPathGUI qupath) {
        if (openTasks.decrementAndGet() > 0) return;
        String installed;
        synchronized (installedThisSession) {
            if (installedThisSession.isEmpty()) return;
            installed = String.join(", ", installedThisSession);
            installedThisSession.clear();
        }
        promptQuit(qupath, installed);
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
                Path p = Path.of(src.getLocation().toURI());
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                    return p.getParent();
                if (Files.isDirectory(p)) return p; // running from classes dir (dev)
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
        try {
            Path dir = extensionsDir(anchor);
            log.info(TAG + "reap {}: extensions dir = {}", module, dir);
            if (!Files.isDirectory(dir)) return;
            String prefix = "qtrace-" + module + "-";
            String bareLegacyName = "qtrace-" + module + ".jar";
            String best = null;
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String v = versionOf(p, prefix);
                    if (v != null && (best == null || compareSemver(v, best) > 0)) best = v;
                }
            }
            if (best == null) {
                log.info(TAG + "reap {}: no versioned JAR found, nothing to do", module);
                return;
            }
            final String keep = best;
            log.info(TAG + "reap {}: keeping version {}", module, keep);
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String v = versionOf(p, prefix);
                    boolean isBareLegacy = p.getFileName().toString().equals(bareLegacyName);
                    if (isBareLegacy || (v != null && compareSemver(v, keep) < 0)) {
                        try {
                            Files.deleteIfExists(p);
                            log.info(TAG + "reap {}: deleted {}", module, p);
                        } catch (Exception e) {
                            log.warn(TAG + "reap {}: could NOT delete {} ({})", module, p, e.toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn(TAG + "reap {} failed: {}", module, e.toString());
        }
    }

    private static String versionOf(Path p, String prefix) {
        String name = p.getFileName().toString();
        if (!name.startsWith(prefix) || !name.endsWith(".jar")) return null;
        Matcher m = JAR_VERSION.matcher(name);
        return m.matches() ? m.group(1) : null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** Compares dotted numeric versions. >0 if a>b, 0 equal, <0 a<b. */
    public static int compareSemver(String a, String b) {
        if (a == null) a = "0";
        if (b == null) b = "0";
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parse(pa[i]) : 0;
            int vb = i < pb.length ? parse(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); }
        catch (Exception e) { return 0; }
    }

    public static byte[] httpGetBytes(String url, String bearer) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS).build();
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(url)).timeout(Duration.ofSeconds(120)).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        HttpResponse<byte[]> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String sha256Hex(byte[] data) {
        return io.qtrace.chain.Hashing.sha256Hex(data);
    }

    private static void info(QuPathGUI qupath, String msg)  { alert(qupath, Alert.AlertType.INFORMATION, msg); }
    private static void error(QuPathGUI qupath, String msg) { alert(qupath, Alert.AlertType.ERROR, msg); }

    /**
     * Post-install prompt: offer to quit QuPath now or later. The user reopens QuPath
     * themselves — the new JAR is only picked up by a fresh JVM. Uses QuPath's own
     * sendQuitRequest() (same path as File > Quit, incl. the unsaved-changes prompt):
     * firing a synthetic WINDOW_CLOSE_REQUEST on the stage was silently ignored.
     */
    private static void promptQuit(QuPathGUI qupath, String installed) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle(QTraceI18n.t("update.title"));
            a.setHeaderText(null);
            a.setContentText(QTraceI18n.t("update.installed").replace("{0}", installed)
                + "\n" + QTraceI18n.t("update.installed.hint"));
            if (qupath != null && qupath.getStage() != null) a.initOwner(qupath.getStage());

            ButtonType quitNow = new ButtonType(QTraceI18n.t("update.quit.now"), ButtonBar.ButtonData.OK_DONE);
            ButtonType later   = new ButtonType(QTraceI18n.t("update.later"),    ButtonBar.ButtonData.CANCEL_CLOSE);
            a.getButtonTypes().setAll(quitNow, later);

            Optional<ButtonType> result = a.showAndWait();
            boolean quit = result.isPresent() && result.get() == quitNow;
            log.info(TAG + "post-install: user chose {}", quit ? "quit now" : "later");
            if (quit && qupath != null) whenNoModalOpen("quit request", () -> {
                log.info(TAG + "post-install: sending quit request to QuPath");
                qupath.sendQuitRequest();
            });
        });
    }

    /**
     * Runs {@code action} on the FX thread once no nested event loop is running, i.e. no
     * modal dialog is open (typically QuPath's own Welcome window at startup). QuPath
     * silently discards a close request issued from a nested loop ("Close request from
     * nested loop - will be discarded"), so both the update prompt and the post-install
     * quit wait for it.
     */
    private static void whenNoModalOpen(String what, Runnable action) {
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

    private static void alert(QuPathGUI qupath, Alert.AlertType type, String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(type);
            a.setTitle(QTraceI18n.t("update.title"));
            a.setHeaderText(null);
            a.setContentText(msg);
            if (qupath != null && qupath.getStage() != null) a.initOwner(qupath.getStage());
            a.show();
        });
    }
}
