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

package io.qtrace.onboarding;

import io.qtrace.BrowserOpener;
import io.qtrace.ModuleUpdates;
import io.qtrace.QTraceUpdater;
import io.qtrace.tools.PlayerBridge;
import io.qtrace.tools.PlayerWindow;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Getting started" played by the HTML player embedded in this module (loader.md § 17): the
 * trunk content (player/trunk.json) and the actions it may run. The plain {@link OnboardingDialog}
 * stays as fallback on a QuPath without WebView.
 */
final class TrunkPlayer {

    private static final Logger log = LoggerFactory.getLogger(TrunkPlayer.class);

    static final String SERVER = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");
    private static final String GITHUB = System.getProperty("qtrace.loader.bootstrap",
        "https://github.com/RomainTourte/qTrace-core/releases/latest/download/qtrace-bootstrap.json");

    private final QuPathGUI qupath;
    private final Path qtraceDir;

    TrunkPlayer(QuPathGUI qupath, Path qtraceDir) {
        this.qupath = qupath;
        this.qtraceDir = qtraceDir;
    }

    /**
     * Opens the player, or the plain dialog when this QuPath has no WebView. The modules open
     * to anyone (GET /api/modules/open) are asked for first — a few seconds at most, nothing
     * offline: each gets its slide, and the ones not on this workstation yet are installed.
     */
    void show() {
        CompletableFuture.supplyAsync(TrunkPlayer::openModules)
            .completeOnTimeout(List.of(), OPEN_MODULES_WAIT_S, TimeUnit.SECONDS)
            .exceptionally(e -> List.of())
            .thenAccept(modules -> Platform.runLater(() -> show(modules)));
    }

    private static final int OPEN_MODULES_WAIT_S = 4;

    /** What the server opens to anyone, with whether each is still to be installed here. */
    private static List<TrunkContent.Module> openModules() {
        Map<String, String> local = ModuleUpdates.localVersions(QTraceUpdater.extensionsDir(QTraceUpdater.class));
        List<ModuleUpdates.OpenModule> open = QTraceUpdater.openModules();
        List<ModuleUpdates.Offer> pending = ModuleUpdates.pending(
            open.stream().map(ModuleUpdates.OpenModule::offer).toList(), local);
        return open.stream().map(m -> new TrunkContent.Module(m.offer().module(), m.title(), m.tagline(),
            m.offer().version(), pending.contains(m.offer()))).toList();
    }

    private void show(List<TrunkContent.Module> modules) {
        boolean installing = modules.stream().anyMatch(TrunkContent.Module::installing);
        // Asked for by opening Getting started: installed without another question, and no
        // dialog of its own — the last slide offers the restart.
        CompletableFuture<Integer> install = installing
            ? QTraceUpdater.installModulesNow(qupath, false) : CompletableFuture.completedFuture(0);
        if (!PlayerBridge.webViewAvailable()) {
            new OnboardingDialog(qupath, qtraceDir).show();
            return;
        }
        String content;
        try (InputStream in = TrunkPlayer.class.getResourceAsStream("player/trunk.json")) {
            content = TrunkContent.withOpenModules(new String(in.readAllBytes(), StandardCharsets.UTF_8), modules);
        } catch (Exception e) {
            log.warn("[qtrace-onboarding] trunk content missing, plain dialog instead", e);
            new OnboardingDialog(qupath, qtraceDir).show();
            return;
        }
        String playerUrl = TrunkPlayer.class.getResource("player/player.html").toExternalForm();

        AtomicBoolean cancelled = new AtomicBoolean();
        PlayerBridge bridge = new PlayerBridge();
        PlayerWindow w = new PlayerWindow(qupath.getStage(), "Getting started with qTrace", playerUrl, content, bridge);

        AtomicBoolean networkChecked = new AtomicBoolean();
        bridge.on("network-check", a -> { if (networkChecked.compareAndSet(false, true)) checkNetwork(w); })
            // One path: with an invitation code or an existing account, the browser does the rest.
            .on("sign-in", a -> signIn(w, cancelled, null))
            .on("invite", code -> signIn(w, cancelled, code))
            .on("continue", a -> {
                OnboardingState.load(qtraceDir).markTrunkDone();
                w.gotoSlide("ready");
            })
            .on("close", a -> w.close())
            .on("quit", a -> {
                w.close();
                QTraceUpdater.whenNoModalOpen("quit request", qupath::sendQuitRequest);
            })
            .on("open-url", BrowserOpener::open);

        w.stage().setOnHidden(e -> cancelled.set(true));
        w.show();
        if (installing) {
            w.emit("modules", Map.of("state", "running"));
            install.whenComplete((count, error) -> Platform.runLater(() ->
                w.emit("modules", Map.of("state", error == null && count > 0 ? "done" : "failed"))));
        }
    }

    private static void checkNetwork(PlayerWindow w) {
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("qtrace.ca", SERVER + "/api/version");
        targets.put("github.com", GITHUB);
        targets.forEach((name, url) -> CompletableFuture.runAsync(() -> {
            boolean ok = NetworkCheck.unreachable(Map.of(name, url)).isEmpty();
            w.emit("network", Map.of("name", name, "ok", ok));
        }));
    }

    private void signIn(PlayerWindow w, AtomicBoolean cancelled, String invite) {
        cancelled.set(false);
        w.gotoSlide("code");
        new SignInFlow(qupath, qtraceDir, SERVER).start(new SignInFlow.Listener() {
            String code = "";
            String url = "";
            public void starting() { w.emit("device", Map.of("state", "starting")); }
            public void waiting(String userCode, String verificationUrl) {
                code = userCode;
                url = verificationUrl;
                w.emit("device", Map.of("state", "waiting", "code", code, "url", url));
            }
            public void approved(Path certificate) { w.emit("device", Map.of("state", "approved", "code", code)); }
            public void installing() {
                w.gotoSlide("install");
                w.emit("install", Map.of("state", "running"));
            }
            public void installed(int modules) {
                w.emit("install", Map.of("state", "done", "count", modules));
            }
            public void failed(String message) {
                w.emit("device", Map.of("state", "failed", "message", message, "code", code));
            }
        }, cancelled::get, false, invite);
    }
}
