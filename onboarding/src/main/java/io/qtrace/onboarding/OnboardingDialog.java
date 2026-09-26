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
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The trunk of the onboarding tree (loader.md § 17): welcome → network check →
 * Sign in / Create an account / Continue with Core only. One window whose content changes.
 */
final class OnboardingDialog {


    // Same overrides as Core's updater, so the local simulator (tools/update-sim/) drives it too.
    static final String SERVER = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");
    private static final String GITHUB = System.getProperty("qtrace.loader.bootstrap",
        "https://github.com/RomainTourte/qTrace-core/releases/latest/download/qtrace-bootstrap.json");

    private final QuPathGUI qupath;
    private final Path qtraceDir;
    private final Stage stage = new Stage();
    private final VBox root = new VBox(14);
    private final AtomicBoolean cancelled = new AtomicBoolean();

    OnboardingDialog(QuPathGUI qupath, Path qtraceDir) {
        this.qupath = qupath;
        this.qtraceDir = qtraceDir;
        if (qupath != null && qupath.getStage() != null) stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle("Getting started with qTrace");
        root.setPadding(new Insets(24));
        root.setPrefWidth(520);
        stage.setScene(new Scene(root));
        stage.setOnHidden(e -> cancelled.set(true));
    }

    void show() {
        welcome();
        stage.show();
    }

    // ── Screens ─────────────────────────────────────────────────────────────────

    private void welcome() {
        Label network = muted("Checking the connection to qtrace.ca and GitHub…");

        Button signIn = primary("Sign in to qtrace.ca");
        signIn.setOnAction(e -> signIn());
        Button create = new Button("Create an account");
        create.setOnAction(e -> {
            BrowserOpener.open(SERVER + "/sign-up");
            network.setText("Your browser opened qtrace.ca. Once your account is ready, "
                + "come back here and click “Sign in to qtrace.ca”.");
        });
        Button coreOnly = new Button("Continue");
        coreOnly.setOnAction(e -> {
            OnboardingState.load(qtraceDir).markTrunkDone();
            stage.close();
        });

        setContent(
            title("Welcome to qTrace"),
            text("qTrace records how you analyse images in QuPath, so every result can be traced, "
                + "replayed and signed with your certified identity."),
            text("Already have a qtrace.ca account? Sign in: your certificate is installed automatically."),
            network,
            new VBox(8, signIn, create, coreOnly),
            muted("You can reopen this window from Extensions > QTrace > Getting started…"));

        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("qtrace.ca", SERVER + "/api/version");
        targets.put("GitHub", GITHUB);
        CompletableFuture.supplyAsync(() -> NetworkCheck.unreachable(targets)).thenAccept(down ->
            Platform.runLater(() -> network.setText(down.isEmpty()
                ? "✓ Connection OK."
                : "⚠ Cannot reach " + String.join(" and ", down) + ". If your institution uses a proxy "
                  + "or a firewall, ask your IT team to allow qtrace.ca and github.com.")));
    }

    private void signIn() {
        cancelled.set(false);
        Label code = new Label("…");
        code.setStyle("-fx-font-size: 28px; -fx-font-family: monospace; -fx-font-weight: bold;");
        Label status = muted("Contacting qtrace.ca…");
        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(22, 22);
        Button reopen = new Button("Open the page again");
        reopen.setDisable(true);
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> { cancelled.set(true); welcome(); });

        setContent(
            title("Sign in to qtrace.ca"),
            text("Your browser opens qtrace.ca. Sign in, check that it shows this code, then click "
                + "“Authorize QuPath”:"),
            code,
            new HBox(10, spin, status),
            new HBox(8, reopen, cancel));

        new SignInFlow(qupath, qtraceDir, SERVER).start(new SignInFlow.Listener() {
            public void starting() { }
            public void waiting(String userCode, String verificationUrl) {
                Platform.runLater(() -> {
                    code.setText(userCode);
                    status.setText("Waiting for your approval in the browser…");
                    reopen.setDisable(false);
                    reopen.setOnAction(e -> BrowserOpener.open(verificationUrl));
                });
            }
            public void approved(Path certificate) { }
            public void installing() { Platform.runLater(OnboardingDialog.this::installing); }
            public void installed(int modules) { Platform.runLater(() -> done(modules)); }
            public void failed(String message) { Platform.runLater(() -> OnboardingDialog.this.failed(message)); }
        }, cancelled::get, true);
    }

    private void installing() {
        ProgressIndicator spin = new ProgressIndicator();
        spin.setPrefSize(22, 22);
        Label status = muted("Installing the modules of your certificate…");
        setContent(
            title("You are signed in"),
            text("Your certificate is installed. qTrace now downloads Compliance and your welcome."),
            new HBox(10, spin, status));
    }

    private void done(int modules) {
        Button close = primary("Close");
        close.setOnAction(e -> stage.close());
        setContent(
            title(modules > 0 ? "Almost done: restart QuPath" : "Your certificate is installed"),
            text(modules > 0
                ? "Restart QuPath to finish. Your welcome will be waiting for you."
                : "Restart QuPath to activate it. If Compliance is still missing afterwards, "
                  + "check your connection and use Extensions > QTrace > Bug or Feature Request…"),
            close);
    }

    private void failed(String why) {
        Button retry = primary("Try again");
        retry.setOnAction(e -> signIn());
        Button back = new Button("Back");
        back.setOnAction(e -> welcome());
        setContent(title("Not connected"), text(why), new HBox(8, retry, back));
    }

    // ── Widgets ─────────────────────────────────────────────────────────────────

    private void setContent(javafx.scene.Node... nodes) {
        root.getChildren().setAll(List.of(nodes));
        stage.sizeToScene();
    }

    private static Label title(String s) {
        Label l = new Label(s);
        l.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        return l;
    }

    private static Label text(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        return l;
    }

    private static Label muted(String s) {
        Label l = text(s);
        l.setStyle("-fx-opacity: 0.75;");
        return l;
    }

    private static Button primary(String s) {
        Button b = new Button(s);
        b.setDefaultButton(true);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER);
        return b;
    }
}
