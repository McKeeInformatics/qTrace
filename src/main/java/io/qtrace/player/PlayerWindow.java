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

package io.qtrace.player;

import com.google.gson.Gson;
import io.qtrace.BrowserOpener;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wide onboarding window (loader.md § 17): a WebView playing the HTML player, fed with its
 * content by Java ({@code qtracePlayer.load}) and driving QuPath only through
 * {@link PlayerBridge} ({@code window.qtrace.run}), and only while the page is the player.
 * Links elsewhere open in the user's browser. Check {@link PlayerBridge#webViewAvailable()}
 * before creating one.
 */
public final class PlayerWindow {

    private static final Logger log = LoggerFactory.getLogger(PlayerWindow.class);
    private static final Gson GSON = new Gson();

    private final String playerUrl;
    private final String contentJson;
    private final PlayerBridge bridge;
    private final Stage stage = new Stage();
    private final WebEngine engine;
    // WebView keeps only a weak reference to objects handed to JavaScript: hold it here.
    private final JsApi api = new JsApi();
    private boolean ready;
    private final java.util.List<String> pending = new java.util.ArrayList<>();

    /** Called from JavaScript as {@code qtrace.run(action, arg)}. Public for the JS bridge. */
    public final class JsApi {
        public void run(String action, String arg) {
            bridge.dispatch(action, arg);
        }
    }

    public PlayerWindow(Window owner, String title, String playerUrl, String contentJson, PlayerBridge bridge) {
        this.playerUrl = playerUrl;
        this.contentJson = contentJson;
        this.bridge = bridge;

        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        engine = view.getEngine();
        engine.setOnAlert(e -> log.info("[qtrace-player] page: {}", e.getData()));
        engine.getLoadWorker().stateProperty().addListener((obs, old, st) -> {
            if (st == Worker.State.SUCCEEDED) onLoaded();
            if (st == Worker.State.FAILED) log.warn("[qtrace-player] could not load {}", engine.getLocation());
        });
        // The player never navigates by itself: any other page is a link, for the browser.
        engine.locationProperty().addListener((obs, old, loc) -> {
            if (loc == null || loc.isEmpty() || PlayerBridge.trustedLocation(playerUrl, loc)) return;
            log.info("[qtrace-player] link opened in the browser: {}", loc);
            BrowserOpener.open(loc);
            Platform.runLater(() -> engine.load(playerUrl));
        });

        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle(title);
        stage.setScene(new Scene(view, 1000, 640));
        stage.setMinWidth(720);
        stage.setMinHeight(520);
    }

    public void show() {
        engine.load(playerUrl);
        stage.show();
    }

    public Stage stage() {
        return stage;
    }

    public void close() {
        Platform.runLater(stage::close);
    }

    /** Sends {@code qtracePlayer.emit(event, data)}; queued until the page is ready. Any thread. */
    public void emit(String event, Object data) {
        script("qtracePlayer.emit(" + GSON.toJson(event) + "," + GSON.toJson(GSON.toJson(data)) + ")");
    }

    /** Shows the slide with this id. Any thread. */
    public void gotoSlide(String id) {
        script("qtracePlayer.goto(" + GSON.toJson(id) + ")");
    }

    private void script(String js) {
        Platform.runLater(() -> {
            if (!ready) { pending.add(js); return; }
            try {
                engine.executeScript(js);
            } catch (RuntimeException e) {
                log.warn("[qtrace-player] script failed: {}", e.getMessage());
            }
        });
    }

    private void onLoaded() {
        if (!PlayerBridge.trustedLocation(playerUrl, engine.getLocation())) return;
        ((JSObject) engine.executeScript("window")).setMember("qtrace", api);
        engine.executeScript("qtracePlayer.load(" + GSON.toJson(contentJson) + ")");
        ready = true;
        pending.forEach(engine::executeScript);
        pending.clear();
    }
}
