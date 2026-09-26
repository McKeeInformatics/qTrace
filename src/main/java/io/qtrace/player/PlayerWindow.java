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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.BrowserOpener;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * The wide onboarding window (loader.md § 17): a WebView playing the HTML player, driving
 * QuPath only through {@link PlayerBridge}, and only while the page is the player. Links
 * elsewhere open in the user's browser. Check {@link PlayerBridge#webViewAvailable()} before
 * creating one.
 *
 * No {@code WebEngine.executeScript} and no JSObject: in QuPath 0.7 (JavaFX 25, classpath mode)
 * executeScript crashes the JVM (SIGSEGV in libjfxwebkit, twkExecuteScript). So:
 * <ul>
 *   <li>Java → page: the whole state (content, device code, install progress, slide to show)
 *       travels in the URL fragment, {@code player.html#<base64url JSON>}; the page renders it
 *       on load and on every {@code hashchange}.</li>
 *   <li>page → Java: {@code alert("qtrace:{action,arg}")}, caught by {@code setOnAlert}.</li>
 * </ul>
 */
public final class PlayerWindow {

    private static final Logger log = LoggerFactory.getLogger(PlayerWindow.class);
    private static final String PREFIX = "qtrace:";

    private final String playerUrl;
    private final PlayerBridge bridge;
    private final Stage stage = new Stage();
    private final WebEngine engine;
    private final JsonObject message = new JsonObject();
    private final JsonObject state = new JsonObject();
    private int seq;

    public PlayerWindow(Window owner, String title, String playerUrl, String contentJson, PlayerBridge bridge) {
        this.playerUrl = playerUrl;
        this.bridge = bridge;
        message.addProperty("host", true);
        message.add("content", JsonParser.parseString(contentJson));
        message.add("state", state);

        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        engine = view.getEngine();
        engine.setOnAlert(e -> onMessage(e.getData()));
        engine.getLoadWorker().stateProperty().addListener((obs, old, st) -> {
            if (st == Worker.State.FAILED) log.warn("[qtrace-player] could not load {}", engine.getLocation());
        });
        // The player never navigates by itself: any other page is a link, for the browser.
        engine.locationProperty().addListener((obs, old, loc) -> {
            if (loc == null || loc.isEmpty() || PlayerBridge.trustedLocation(playerUrl, loc)) return;
            log.info("[qtrace-player] link opened in the browser: {}", loc);
            BrowserOpener.open(loc);
            Platform.runLater(this::push);
        });

        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle(title);
        stage.setScene(new Scene(view, 1000, 640));
        stage.setMinWidth(720);
        stage.setMinHeight(520);
    }

    public void show() {
        push();
        stage.show();
    }

    public Stage stage() {
        return stage;
    }

    public void close() {
        Platform.runLater(stage::close);
    }

    /**
     * Updates one part of the state the page renders: {@code network} merges by
     * {@code name} → {@code ok}; any other event replaces its entry. Any thread.
     */
    public void emit(String event, Map<String, ?> data) {
        Platform.runLater(() -> {
            JsonObject d = GSON.toJsonTree(data).getAsJsonObject();
            if ("network".equals(event)) {
                JsonObject net = state.has("network") ? state.getAsJsonObject("network") : new JsonObject();
                net.add(d.get("name").getAsString(), d.get("ok"));
                state.add("network", net);
            } else {
                state.add(event, d);
            }
            push();
        });
    }

    /** Shows the slide with this id. Any thread. */
    public void gotoSlide(String id) {
        Platform.runLater(() -> {
            JsonObject g = new JsonObject();
            g.addProperty("id", id);
            g.addProperty("seq", ++seq);
            message.add("goto", g);
            push();
        });
    }

    private void push() {
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(message.toString().getBytes(StandardCharsets.UTF_8));
        engine.load(playerUrl + "#" + payload);
    }

    /** alert("qtrace:" + JSON) from the player (player.js run); anything else is page logging. */
    private void onMessage(String data) {
        if (data == null || !data.startsWith(PREFIX)) {
            log.info("[qtrace-player] page: {}", data);
            return;
        }
        if (!PlayerBridge.trustedLocation(playerUrl, engine.getLocation())) return;
        try {
            JsonObject m = JsonParser.parseString(data.substring(PREFIX.length())).getAsJsonObject();
            String action = m.has("action") ? m.get("action").getAsString() : null;
            String arg = m.has("arg") && !m.get("arg").isJsonNull() ? m.get("arg").getAsString() : "";
            bridge.dispatch(action, arg);
        } catch (RuntimeException e) {
            log.warn("[qtrace-player] unreadable message: {}", data);
        }
    }

    private static final Gson GSON = new Gson();
}
