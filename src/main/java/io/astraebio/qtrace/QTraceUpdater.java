package io.astraebio.qtrace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import qupath.lib.gui.QuPathGUI;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup update mechanism shared by Core (public GitHub) and Enterprise (qtrace.ca).
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

    private static final String GITHUB_LATEST =
        "https://api.github.com/repos/RomainTourte/qTrace-core/releases/latest";
    private static final String VERSION_URL =
        "https://q-trace-alpha.vercel.app/api/version";
    private static final String ENT_DOWNLOAD_URL =
        "https://q-trace-alpha.vercel.app/api/download/enterprise/licensed";

    private static final Pattern JAR_VERSION =
        Pattern.compile("^qtrace-(?:core|enterprise)-(\\d+(?:\\.\\d+)*)\\.jar$");

    @FunctionalInterface
    public interface Downloader { byte[] download() throws Exception; }

    // ── Core check (public GitHub release) ──────────────────────────────────────

    /** Async, safe. Offers an update if the latest qTrace-core GitHub release is newer. */
    public static void checkCore(QuPathGUI qupath) {
        if (!QTraceConfig.get().isUpdateCheckEnabled()) return;
        CompletableFuture.runAsync(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_LATEST))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return;

                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                String tag = json.has("tag_name") ? json.get("tag_name").getAsString() : null;
                if (tag == null) return;
                String remote = tag.startsWith("v") ? tag.substring(1) : tag;

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
                if (assetUrl == null) return;

                final String url = assetUrl;
                Downloader dl = () -> httpGetBytes(url, null);
                promptAndInstall(qupath, "core", QTraceController.VERSION, remote, null, dl);
            } catch (Exception ignored) {
                // offline / rate-limited — stay silent
            }
        });
    }

    // ── Enterprise check (driven by Core, works with any enterprise JAR) ─────────

    /**
     * Async, safe. Offers an Enterprise update based on the qtrace.ca manifest.
     * Driven by the Core (not the enterprise JAR) so it works even when the
     * installed enterprise JAR predates the auto-update feature — it only reads
     * the loaded plugin's reported version and the license from config.
     */
    public static void checkEnterprise(QuPathGUI qupath, QTracePlugin ep) {
        if (ep == null || !QTraceConfig.get().isUpdateCheckEnabled()) return;
        final String currentVer = ep.getPluginVersion();
        CompletableFuture.runAsync(() -> {
            try {
                String jwt = licenseJwt();
                if (jwt == null) return; // no license → can't authenticate the download

                byte[] mb = httpGetBytes(VERSION_URL, null);
                JsonObject manifest = JsonParser
                    .parseString(new String(mb, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!manifest.has("enterprise")) return;
                JsonObject ent = manifest.getAsJsonObject("enterprise");
                String remoteVer = ent.has("version") ? ent.get("version").getAsString() : null;
                String sha256    = ent.has("sha256")  ? ent.get("sha256").getAsString()  : null;
                if (remoteVer == null) return;

                Downloader dl = () -> httpGetBytes(ENT_DOWNLOAD_URL, jwt);
                promptAndInstall(qupath, "enterprise", currentVer, remoteVer, sha256, dl);
            } catch (Exception ignored) {
                // offline / no license — stay silent
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
     * Safe to call from any thread.
     */
    public static void promptAndInstall(QuPathGUI qupath, String module, String currentVer,
                                        String remoteVer, String expectedSha256, Downloader downloader) {
        if (remoteVer == null || compareSemver(remoteVer, currentVer) <= 0) return;
        if (remoteVer.equals(QTraceConfig.get().getDismissedUpdateVersion())) return;

        Platform.runLater(() -> {
            String label = "core".equals(module) ? "Core" : "Enterprise";
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
            if (result.isEmpty() || result.get() == later) return;
            if (result.get() == ignore) {
                QTraceConfig.get().setDismissedUpdateVersion(remoteVer);
                QTraceConfig.get().save();
                return;
            }
            // Install → background download + write
            CompletableFuture.runAsync(() ->
                downloadAndInstall(qupath, module, remoteVer, expectedSha256, downloader));
        });
    }

    private static void downloadAndInstall(QuPathGUI qupath, String module, String remoteVer,
                                           String expectedSha256, Downloader downloader) {
        try {
            byte[] data = downloader.download();
            if (data == null || data.length == 0) throw new Exception("empty download");
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                String actual = sha256Hex(data);
                if (!actual.equalsIgnoreCase(expectedSha256))
                    throw new Exception("SHA-256 mismatch (expected " + expectedSha256 + ", got " + actual + ")");
            }

            Path dir = extensionsDir(QTraceUpdater.class);
            Path target = dir.resolve("qtrace-" + module + "-" + remoteVer + ".jar");
            Path tmp = dir.resolve("qtrace-" + module + "-" + remoteVer + ".jar.part");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);

            info(qupath, QTraceI18n.t("update.installed").replace("{0}", remoteVer));
        } catch (Exception e) {
            error(qupath, QTraceI18n.t("update.failed").replace("{0}", e.getMessage()));
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

    /** Removes versioned JARs for {@code module} older than the highest present (best-effort). */
    public static void reapOldJars(Class<?> anchor, String module) {
        try {
            Path dir = extensionsDir(anchor);
            if (!Files.isDirectory(dir)) return;
            String prefix = "qtrace-" + module + "-";
            String best = null;
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String v = versionOf(p, prefix);
                    if (v != null && (best == null || compareSemver(v, best) > 0)) best = v;
                }
            }
            if (best == null) return;
            final String keep = best;
            try (var s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String v = versionOf(p, prefix);
                    if (v != null && compareSemver(v, keep) < 0) {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
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

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void info(QuPathGUI qupath, String msg)  { alert(qupath, Alert.AlertType.INFORMATION, msg); }
    private static void error(QuPathGUI qupath, String msg) { alert(qupath, Alert.AlertType.ERROR, msg); }

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
