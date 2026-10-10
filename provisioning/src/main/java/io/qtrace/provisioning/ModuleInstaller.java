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

package io.qtrace.provisioning;

import io.qtrace.JarInstaller;
import io.qtrace.ModuleEntitlements;
import io.qtrace.ModuleUpdates;
import io.qtrace.QTraceConfig;
import io.qtrace.QTraceI18n;
import io.qtrace.QTraceUpdater;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything that decides which qTrace modules are on this workstation (loader.md § 7, § 17):
 * what it is served, what is new, what to download. Core no longer downloads anything; it
 * only keeps the answer ({@link ModuleEntitlements}) to switch off what is no longer served.
 *
 * Design (decided with the maintainer):
 *  - Never silent, except when the user just asked (Getting started, Sign in): the user is
 *    prompted before anything is downloaded or installed, once per startup for every module.
 *  - Never hot-swap: a loaded classloader can't be replaced safely (Windows file locks), so
 *    the new file is written to extensions/qtrace/ and applied by the loader at the next start.
 *  - Integrity: the download's SHA-256 is checked against the descriptor's before it is
 *    written; the loader checks the signature before loading.
 */
final class ModuleInstaller {

    private ModuleInstaller() {}

    private static final Logger log = LoggerFactory.getLogger(ModuleInstaller.class);
    private static final String TAG = "[qtrace-update] ";

    // One startup session may update several modules. Each check, then each install, is an
    // open task until it resolves; when the last check closes, the updates found are offered in
    // ONE message, and when the last install closes, the single "installed — Quit QuPath Now"
    // message is shown.
    private static final AtomicInteger openTasks = new AtomicInteger();
    private static final List<String> installedThisSession = new ArrayList<>();
    // Updates found by this session's checks: shown together, in one message, once every check
    // has answered (see taskDone) — one prompt per module made a startup a row of popups.
    private static final List<UpdateBatch.Item> offered = new ArrayList<>();
    // installModulesNow(qupath, false): its caller shows the restart itself.
    private static volatile boolean quietInstall;

    // Overridable for the local update simulator (tools/update-sim/), like Core's own calls.
    // www.qtrace.ca (not the apex) — qtrace.ca 308-redirects to www, and a cross-host redirect
    // makes java.net.http.HttpClient drop the Authorization header (HTTP 401).
    private static final String SERVER = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");
    // Same descriptors as the loader.
    private static final String BOOTSTRAP_URL = System.getProperty("qtrace.loader.bootstrap",
        "https://github.com/RomainTourte/qTrace-core/releases/latest/download/qtrace-bootstrap.json");
    private static final String MODULES_URL = SERVER + "/api/modules";
    private static final String OPEN_MODULES_URL = SERVER + "/api/modules/open";

    // Getting started has the open modules installed itself: the startup check then leaves the
    // ones not installed yet to it, instead of asking over its window.
    private static volatile boolean openModulesLeftToGettingStarted;

    static void leaveOpenModulesToGettingStarted() { openModulesLeftToGettingStarted = true; }

    /**
     * The modules anyone may install, account or not, as the server lists them now
     * (GET /api/modules/open). Blocking; empty offline or with a server that has no such list.
     */
    static List<ModuleUpdates.Card> openModules() {
        try {
            return ModuleUpdates.parseCards(new String(QTraceUpdater.httpGetBytes(OPEN_MODULES_URL, null), StandardCharsets.UTF_8), false);
        } catch (Exception e) {
            log.info(TAG + "open modules descriptor unavailable: {}", e.toString());
            return List.of();
        }
    }

    /**
     * Every module this workstation is served, with its card — the open ones and, with a
     * license, the ones it includes. Blocking; what could not be fetched is simply absent.
     * Read by the Welcome module to present what an installed module's new version brings.
     */
    static List<ModuleUpdates.Card> moduleCards() {
        java.util.Map<String, ModuleUpdates.Card> byModule = new java.util.LinkedHashMap<>();
        for (ModuleUpdates.Card c : openModules()) byModule.putIfAbsent(c.offer().module(), c);
        String jwt = QTraceUpdater.bearer();
        if (jwt != null) {
            try {
                for (ModuleUpdates.Card c : ModuleUpdates.parseCards(
                        new String(QTraceUpdater.httpGetBytes(MODULES_URL, jwt), StandardCharsets.UTF_8), true))
                    byModule.putIfAbsent(c.offer().module(), c);
            } catch (Exception e) {
                log.info(TAG + "licensed modules descriptor unavailable: {}", e.toString());
            }
        }
        return new ArrayList<>(byModule.values());
    }
    @FunctionalInterface
    interface Downloader { byte[] download() throws Exception; }

    // ── What this workstation is served ─────────────────────────────────────────

    /**
     * Async, safe: asks the server what this workstation is served — the modules open to anyone,
     * and with a certificate the ones it includes (and its organization's panel) — and hands
     * the answer to Core, which switches off the installed modules no longer served
     * ({@link ModuleEntitlements}). Offline, or a refused certificate: the last answer stands.
     */
    static void refreshServed() {
        String jwt = QTraceUpdater.bearer();
        CompletableFuture.runAsync(() -> {
            try {
                ModuleEntitlements.accept(fetch(OPEN_MODULES_URL, null), jwt == null ? null : fetch(MODULES_URL, jwt), jwt != null);
            } catch (Exception e) {
                log.info(TAG + "served modules check skipped: {}", e.toString());
            }
        });
    }

    /** A descriptor URL's answer; null when it could not be read. */
    private static String fetch(String url, String jwt) {
        try {
            return new String(QTraceUpdater.httpGetBytes(url, jwt), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.info(TAG + "{} unavailable: {}", url, e.toString());
            return null;
        }
    }

    // ── Updates: every module from the loader's descriptors ─────────────────────

    /**
     * Async, safe. Under the loader: offers every module of the release bootstrap, of the open
     * list (/api/modules/open, no account needed) and, with a license, of /api/modules that is
     * newer than what extensions/qtrace/ already holds
     * (a version downloaded but not loaded yet is not offered again). Nothing is deleted —
     * the loader keeps/cleans versions at the next start.
     */
    static void checkModules(QuPathGUI qupath) {
        if (!QTraceConfig.get().isUpdateCheckEnabled()) {
            log.info(TAG + "modules check skipped: update check disabled in config");
            return;
        }
        openTasks.incrementAndGet();
        CompletableFuture.runAsync(() -> {
            try {
                List<ModuleUpdates.Offer> remote = new ArrayList<>();
                try {
                    remote.addAll(ModuleUpdates.parse(
                        new String(QTraceUpdater.httpGetBytes(BOOTSTRAP_URL, null), StandardCharsets.UTF_8), false));
                } catch (Exception e) {
                    log.info(TAG + "bootstrap descriptor unavailable: {}", e.toString());
                }
                Path dir = QTraceUpdater.extensionsDir(QTraceUpdater.class);
                var local = ModuleUpdates.localVersions(dir);
                for (ModuleUpdates.Card m : openModules()) {
                    // Not installed yet and Getting started is about to do it: not asked twice.
                    if (openModulesLeftToGettingStarted && !local.containsKey(m.offer().module())) continue;
                    remote.add(m.offer());
                }
                String jwt = QTraceUpdater.bearer();
                if (jwt != null) {
                    try {
                        remote = ModuleUpdates.merge(remote, ModuleUpdates.parse(
                            new String(QTraceUpdater.httpGetBytes(MODULES_URL, jwt), StandardCharsets.UTF_8), true));
                    } catch (Exception e) {
                        log.info(TAG + "licensed modules descriptor unavailable: {}", e.toString());
                    }
                }
                log.info(TAG + "modules check: local={} remote={}", local,
                    remote.stream().map(o -> o.module() + " " + o.version()).toList());
                for (ModuleUpdates.Offer o : ModuleUpdates.pending(remote, local)) {
                    openTasks.incrementAndGet();
                    final String bearer = o.licensed() ? jwt : null;
                    Downloader dl = () -> QTraceUpdater.httpGetBytes(o.url(), bearer);
                    promptAndInstall(qupath, o.module(), local.getOrDefault(o.module(), "0"), o.version(), o.sha256(), dl);
                    taskDone(qupath);
                }
            } catch (Exception e) {
                log.info(TAG + "modules check failed: {}", e.toString());
            } finally {
                taskDone(qupath);
            }
        });
    }

    /**
     * Async. Under the loader, from Getting started and right after a license was obtained
     * (provisioning "Sign in", loader.md § 17): downloads every module of the open list
     * (/api/modules/open) and, with a license, of /api/modules not yet in extensions/qtrace/,
     * without asking — the user just asked for it. Ends with the usual single "installed —
     * Quit QuPath Now" dialog. Completes with the number of modules it tried to install (0: nothing to
     * install or offline — the caller tells the user).
     */
    static CompletableFuture<Integer> installModulesNow(QuPathGUI qupath) {
        return installModulesNow(qupath, true);
    }

    /**
     * Same, with {@code quitDialog=false} for a caller that offers the restart itself (the
     * provisioning player's "Quit QuPath now"): no post-install dialog for this session's installs.
     */
    static CompletableFuture<Integer> installModulesNow(QuPathGUI qupath, boolean quitDialog) {
        if (!quitDialog) quietInstall = true;
        openTasks.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            try {
                String jwt = QTraceUpdater.bearer();
                List<ModuleUpdates.Offer> remote = new ArrayList<>(openModules().stream().map(ModuleUpdates.Card::offer).toList());
                if (jwt != null) {
                    remote = ModuleUpdates.merge(remote, ModuleUpdates.parse(
                        new String(QTraceUpdater.httpGetBytes(MODULES_URL, jwt), StandardCharsets.UTF_8), true));
                }
                Path dir = QTraceUpdater.extensionsDir(QTraceUpdater.class);
                for (ModuleUpdates.Offer o : ModuleUpdates.pending(remote, ModuleUpdates.localVersions(dir))) {
                    openTasks.incrementAndGet();
                    final String bearer = o.licensed() ? jwt : null;
                    downloadAndInstall(qupath, o.module(), o.version(), o.sha256(), () -> QTraceUpdater.httpGetBytes(o.url(), bearer));
                    count++;
                }
                log.info(TAG + "install now: {} module(s) from /api/modules{}", count, jwt == null ? "/open" : " and /api/modules/open");
            } catch (Exception e) {
                log.info(TAG + "install now failed: {}", e.toString());
            } finally {
                taskDone(qupath);
            }
            return count;
        });
    }

    // ── Shared install flow ─────────────────────────────────────────────────────

    /**
     * Notes that {@code module} has a newer version. Nothing is shown here: every update found
     * by this session's checks is offered in one message once they have all answered
     * ({@link #taskDone}), and installed together on confirmation. Safe to call from any thread.
     * Always returns false — the caller still closes its own open task.
     */
    private static boolean promptAndInstall(QuPathGUI qupath, String module, String currentVer,
                                        String remoteVer, String expectedSha256, Downloader downloader) {
        if (remoteVer == null || QTraceUpdater.compareSemver(remoteVer, currentVer) <= 0) {
            log.info(TAG + "{}: up to date (local={} remote={})", module, currentVer, remoteVer);
            return false;
        }
        log.info(TAG + "{}: update found {} → {}", module, currentVer, remoteVer);
        synchronized (offered) {
            offered.add(new UpdateBatch.Item(module, currentVer, remoteVer, expectedSha256, downloader));
        }
        return false;
    }

    /**
     * The one update message of a startup: the extensions to update, then Install (all of
     * them), Later, or Skip these versions. Installing ends with the single "installed — Quit
     * QuPath Now" message ({@link #promptQuit}).
     */
    private static void promptUpdates(QuPathGUI qupath, UpdateBatch batch) {
        if (batch.wasSkipped(QTraceConfig.get().getDismissedUpdateVersion())) {
            log.info(TAG + "{} skipped by the user earlier", batch.signature());
            return;
        }
        log.info(TAG + "offering {}", batch.signature());
        boolean one = batch.items().size() == 1;

        // Shown only once no modal dialog is open (QuPath's Welcome window at startup):
        // stacked on top of it, the post-install "Quit QuPath Now" could never work.
        Platform.runLater(() -> QTraceUpdater.whenNoModalOpen("update prompt", () -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(QTraceI18n.t("update.title"));
            alert.setHeaderText(QTraceI18n.t(one ? "update.available.one" : "update.available.many"));
            alert.setContentText(String.join("\n", batch.lines()) + "\n\n" + QTraceI18n.t("update.prompt"));
            if (qupath != null && qupath.getStage() != null) alert.initOwner(qupath.getStage());

            ButtonType install = new ButtonType(QTraceI18n.t("update.install"), ButtonBar.ButtonData.OK_DONE);
            ButtonType later   = new ButtonType(QTraceI18n.t("update.later"),   ButtonBar.ButtonData.CANCEL_CLOSE);
            ButtonType ignore  = new ButtonType(QTraceI18n.t(one ? "update.ignore" : "update.ignore.many"), ButtonBar.ButtonData.OTHER);
            alert.getButtonTypes().setAll(install, later, ignore);

            Optional<ButtonType> result = alert.showAndWait();
            log.info(TAG + "update prompt: user chose {}", result.map(ButtonType::getText).orElse("<closed>"));
            if (result.isEmpty() || result.get() == later) return;
            if (result.get() == ignore) {
                QTraceConfig.get().setDismissedUpdateVersion(batch.signature());
                QTraceConfig.get().save();
                return;
            }
            // Install → every extension, one after the other, in the background; the last
            // one to finish brings the single post-install message.
            openTasks.addAndGet(batch.items().size());
            CompletableFuture.runAsync(() -> {
                for (UpdateBatch.Item it : batch.items())
                    downloadAndInstall(qupath, it.module(), it.remoteVer(), it.sha256(), it.downloader());
            });
        }));
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

            Path target = JarInstaller.install(QTraceUpdater.extensionsDir(QTraceUpdater.class), module, remoteVer, data, ".qtjar");
            log.info(TAG + "{}: wrote {}", module, target);
            // Nothing is deleted: the loader keeps and cleans versions at the next start.

            synchronized (installedThisSession) {
                installedThisSession.add(UpdateBatch.moduleLabel(module) + " v" + remoteVer);
            }
        } catch (Exception e) {
            log.warn(TAG + "{}: install of {} failed", module, remoteVer, e);
            error(qupath, QTraceI18n.t("update.failed").replace("{0}", e.getMessage()));
        } finally {
            taskDone(qupath);
        }
    }

    /**
     * Closes one open task. The last one shows the one update message when the checks found
     * something to update, or the single post-install message when installs just finished.
     */
    private static void taskDone(QuPathGUI qupath) {
        if (openTasks.decrementAndGet() > 0) return;
        UpdateBatch batch;
        synchronized (offered) {
            batch = UpdateBatch.of(offered);
            offered.clear();
        }
        if (!batch.isEmpty()) {
            promptUpdates(qupath, batch);
            return;
        }
        String installed;
        synchronized (installedThisSession) {
            if (installedThisSession.isEmpty()) return;
            installed = String.join(", ", installedThisSession);
            installedThisSession.clear();
        }
        if (quietInstall) { quietInstall = false; return; }
        promptQuit(qupath, installed);
    }

    private static String sha256Hex(byte[] data) {
        return io.qtrace.chain.Hashing.sha256Hex(data);
    }

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
            if (quit && qupath != null) quitQuPath(qupath);
        });
    }

    /**
     * A non-blocking notice with "Quit QuPath Now" and "Later" (Esc answers Later), for a caller
     * that has something installed which needs a restart (the certificate that arrived by itself).
     * Same quit as {@link #promptQuit}. Call on the FX thread.
     */
    static void offerRestart(QuPathGUI qupath, String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initModality(javafx.stage.Modality.NONE);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(text);
        if (qupath != null && qupath.getStage() != null) a.initOwner(qupath.getStage());

        ButtonType quitNow = new ButtonType(QTraceI18n.t("update.quit.now"), ButtonBar.ButtonData.OK_DONE);
        ButtonType later   = new ButtonType(QTraceI18n.t("update.later"),    ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(quitNow, later);
        a.resultProperty().addListener((obs, was, chosen) -> {
            boolean quit = chosen == quitNow;
            log.info(TAG + "restart notice: user chose {}", quit ? "quit now" : "later");
            if (quit && qupath != null) quitQuPath(qupath);
        });
        a.show();
    }

    /** QuPath's own quit (same path as File > Quit, incl. the unsaved-changes prompt), once no modal dialog is open. */
    private static void quitQuPath(QuPathGUI qupath) {
        QTraceUpdater.whenNoModalOpen("quit request", () -> {
            log.info(TAG + "post-install: sending quit request to QuPath");
            qupath.sendQuitRequest();
        });
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
