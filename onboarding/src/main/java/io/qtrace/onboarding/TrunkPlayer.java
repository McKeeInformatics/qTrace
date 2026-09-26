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
import io.qtrace.QTraceUpdater;
import io.qtrace.player.PlayerBridge;
import io.qtrace.player.PlayerWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    /** Opens the player, or the plain dialog when this QuPath has no WebView. */
    void show() {
        if (!PlayerBridge.webViewAvailable()) {
            new OnboardingDialog(qupath, qtraceDir).show();
            return;
        }
        String content;
        try (InputStream in = TrunkPlayer.class.getResourceAsStream("player/trunk.json")) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[qtrace-onboarding] trunk content missing, plain dialog instead", e);
            new OnboardingDialog(qupath, qtraceDir).show();
            return;
        }
        String playerUrl = TrunkPlayer.class.getResource("player/player.html").toExternalForm();

        AtomicBoolean cancelled = new AtomicBoolean();
        PlayerBridge bridge = new PlayerBridge();
        PlayerWindow w = new PlayerWindow(qupath.getStage(), "Getting started with qTrace", playerUrl, content, bridge);

        bridge.on("network-check", a -> checkNetwork(w))
            .on("sign-in", a -> signIn(w, cancelled))
            .on("invite", code -> {
                BrowserOpener.open(SERVER + "/sign-up?invite=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
                w.emit("invite", Map.of("state", "opened"));
            })
            .on("continue", a -> {
                OnboardingState.load(qtraceDir).markTrunkDone();
                w.close();
            })
            .on("quit", a -> {
                w.close();
                QTraceUpdater.whenNoModalOpen("quit request", qupath::sendQuitRequest);
            })
            .on("open-url", BrowserOpener::open);

        w.stage().setOnHidden(e -> cancelled.set(true));
        w.show();
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

    private void signIn(PlayerWindow w, AtomicBoolean cancelled) {
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
        }, cancelled::get, false);
    }
}
