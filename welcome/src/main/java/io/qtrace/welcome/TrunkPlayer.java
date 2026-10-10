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

package io.qtrace.welcome;

import io.qtrace.BrowserOpener;
import io.qtrace.Provisioner;
import io.qtrace.Provisioner.OpenModule;
import io.qtrace.QTraceUpdater;
import io.qtrace.tools.PlayerBridge;
import io.qtrace.tools.PlayerWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Getting started" played by the HTML player embedded in this module (loader.md § 17): the
 * trunk content (player/trunk.json) and the actions it may run. This window only presents:
 * installing the modules and signing in are the provisioning module's work ({@link Provisioner}).
 * The plain {@link GettingStartedDialog} stays as fallback on a QuPath without WebView.
 */
final class TrunkPlayer {

    private static final Logger log = LoggerFactory.getLogger(TrunkPlayer.class);

    private final QuPathGUI qupath;
    private final Provisioner provisioner;

    TrunkPlayer(QuPathGUI qupath, Provisioner provisioner) {
        this.qupath = qupath;
        this.provisioner = provisioner;
    }

    /**
     * Opens the player, or the plain dialog when this QuPath has no WebView. The provisioning
     * module is first asked for the modules open to anyone: the ones not on this workstation
     * yet are being installed, and the last slide offers the restart. Each tool's own welcome
     * plays after it, when the tool shows in the panel.
     */
    void show() {
        showAt(null);
    }

    /** The slide where the user says whether they have an invitation code or an account. */
    static final String ACCOUNT = "account";

    /** Same, opened straight on the slide {@code slide} (null: from the start). */
    void showAt(String slide) {
        provisioner.provisionOpenModules().thenAccept(modules -> show(modules, slide));
    }

    private void show(List<OpenModule> modules, String slide) {
        if (!PlayerBridge.webViewAvailable()) {
            new GettingStartedDialog(qupath, provisioner).show();
            return;
        }
        String content;
        try (InputStream in = TrunkPlayer.class.getResourceAsStream("player/trunk.json")) {
            content = TrunkContent.withOpenModules(new String(in.readAllBytes(), StandardCharsets.UTF_8), modules);
        } catch (Exception e) {
            log.warn("[qtrace-welcome] Getting started content missing, plain dialog instead", e);
            new GettingStartedDialog(qupath, provisioner).show();
            return;
        }
        String playerUrl = TrunkPlayer.class.getResource("player/player.html").toExternalForm();

        AtomicBoolean cancelled = new AtomicBoolean();
        PlayerBridge bridge = new PlayerBridge();
        PlayerWindow w = new PlayerWindow(qupath.getStage(), "Getting started with qTrace", playerUrl, content, bridge);

        AtomicBoolean networkChecked = new AtomicBoolean();
        bridge.on("network-check", a -> {
                if (networkChecked.compareAndSet(false, true))
                    provisioner.checkNetwork((name, ok) -> w.emit("network", Map.of("name", name, "ok", ok)));
            })
            // One path: with an invitation code or an existing account, the browser does the rest.
            .on("sign-in", a -> signIn(w, cancelled, null))
            .on("invite", code -> signIn(w, cancelled, code))
            .on("continue", a -> {
                provisioner.gettingStartedDone();
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
        if (slide != null) w.gotoSlide(slide);
    }

    private void signIn(PlayerWindow w, AtomicBoolean cancelled, String invite) {
        cancelled.set(false);
        w.gotoSlide("code");
        provisioner.signIn(new Provisioner.SignIn() {
            String code = "";
            String url = "";
            public void starting() { w.emit("device", Map.of("state", "starting")); }
            public void waiting(String userCode, String verificationUrl) {
                code = userCode;
                url = verificationUrl;
                w.emit("device", Map.of("state", "waiting", "code", code, "url", url));
            }
            public void approved(Path certificate) { w.emit("device", Map.of("state", "approved", "code", code)); }
            public void signedInToAccount(String name) {
                w.emit("device", Map.of("state", "account", "name", name == null ? "" : name, "code", code));
            }
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
