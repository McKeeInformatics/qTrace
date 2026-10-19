/*
 * qTrace - QuPath workflow provenance extension
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

import io.qtrace.net.ConnectionTest;
import io.qtrace.net.NetworkSettings;
import io.qtrace.net.NetworkSettingsStore;
import io.qtrace.net.QTraceHttp;
import io.qtrace.net.SystemProxy;
import io.qtrace.net.TrustStores;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static io.qtrace.QTraceSettingsDialog.*;

/**
 * Settings › Network: the proxy (this computer's, none, or one given by IT with an optional
 * sign-in), the certificates trusted on top of Java's (this computer's store, an institution root
 * certificate), and "Test connection to qtrace.ca". What it saves is used by every qTrace HTTP
 * request ({@link QTraceHttp}).
 */
final class NetworkSettingsPane {

    private static final String SERVER = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");
    private static final URI PROBE = URI.create(SERVER + "/api/version");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final Stage owner;
    private final NetworkSettings saved;
    private final VBox root;

    private final RadioButton rbSystem = radio("settings.network.mode.system", "network-mode-system");
    private final RadioButton rbNone = radio("settings.network.mode.none", "network-mode-none");
    private final RadioButton rbManual = radio("settings.network.mode.manual", "network-mode-manual");
    private final Label detected = muted("…");
    private final TextField tfHost = pathField("");
    private final TextField tfPort = pathField("");
    private final TextField tfUser = pathField("");
    private final PasswordField pfPassword = new PasswordField();
    private final GridPane manualGrid = new GridPane();
    private final CheckBox chkSystemStore = new CheckBox(t("settings.network.trustsystem"));
    private final Label storeStatus = muted("…");
    private final TextField tfCa = pathField("");
    private final Label caStatus = new Label();
    private final VBox results = new VBox(8);
    private final Button btnTest = new Button(t("settings.network.test"));
    private final ProgressIndicator spinner = new ProgressIndicator();
    private ConnectionTest.Report lastReport;

    NetworkSettingsPane(Stage owner) {
        this.owner = owner;
        this.saved = QTraceHttp.load();
        this.root = build();
        fill(saved);
    }

    Node node() { return root; }

    private static String t(String key, Object... args) { return QTraceI18n.f(key, args); }

    // ── Layout ─────────────────────────────────────────────────────────────────

    private VBox build() {
        Label intro = new Label(t("settings.network.intro"));
        Theme.text(intro, TEXT_SUB);
        intro.setFont(Font.font("System", 11));
        intro.setWrapText(true);

        ToggleGroup mode = new ToggleGroup();
        for (RadioButton rb : List.of(rbSystem, rbNone, rbManual)) rb.setToggleGroup(mode);
        mode.selectedToggleProperty().addListener((o, a, b) -> refreshMode());

        // Manual proxy: address and port on one row, then the optional sign-in.
        tfHost.setId("network-proxy-host");
        tfHost.setPromptText(t("settings.network.host.prompt"));
        tfPort.setId("network-proxy-port");
        tfPort.setPromptText("3128");
        tfPort.setPrefWidth(72);
        tfPort.setMaxWidth(72);
        tfPort.setTextFormatter(new javafx.scene.control.TextFormatter<String>(c ->
            c.getControlNewText().matches("\\d{0,5}") ? c : null));
        tfUser.setId("network-proxy-user");
        tfUser.setPromptText(t("settings.network.user.prompt"));
        pfPassword.setId("network-proxy-password");
        pfPassword.setPrefHeight(30);
        pfPassword.setStyle(tfUser.getStyle());

        manualGrid.setHgap(8);
        manualGrid.setVgap(8);
        manualGrid.setPadding(new Insets(2, 0, 0, 24));
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(92);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setFillWidth(true);
        manualGrid.getColumnConstraints().addAll(c0, c1, new ColumnConstraints(), new ColumnConstraints());
        manualGrid.add(fieldLabel("settings.network.host"), 0, 0);
        manualGrid.add(tfHost, 1, 0);
        manualGrid.add(fieldLabel("settings.network.port"), 2, 0);
        manualGrid.add(tfPort, 3, 0);
        manualGrid.add(fieldLabel("settings.network.user"), 0, 1);
        manualGrid.add(tfUser, 1, 1, 3, 1);
        manualGrid.add(fieldLabel("settings.network.password"), 0, 2);
        manualGrid.add(pfPassword, 1, 2, 3, 1);
        Label signinHint = hint(t("settings.network.signin.hint"));
        manualGrid.add(signinHint, 1, 3, 3, 1);

        VBox.setMargin(detected, new Insets(-4, 0, 0, 24));

        // Certificates.
        chkSystemStore.setId("network-trust-system");
        Theme.text(chkSystemStore, TEXT_SUB);
        chkSystemStore.setWrapText(true);
        chkSystemStore.setTooltip(hintTooltip(t("settings.network.trustsystem.hint")));
        VBox.setMargin(storeStatus, new Insets(-4, 0, 0, 24));

        tfCa.setId("network-ca-file");
        tfCa.setEditable(false);
        tfCa.setPromptText(t("settings.network.ca.prompt"));
        Button btnBrowse = flatButton("Browse…", TEXT_MUTED);
        btnBrowse.setOnAction(e -> chooseCa());
        Button btnRemove = flatButton(t("settings.network.ca.remove"), TEXT_MUTED);
        btnRemove.setId("network-ca-remove");
        btnRemove.disableProperty().bind(tfCa.textProperty().isEmpty());
        btnRemove.setOnAction(e -> { tfCa.clear(); refreshCa(); });

        GridPane caGrid = new GridPane();
        caGrid.setHgap(8);
        caGrid.setVgap(6);
        ColumnConstraints l = new ColumnConstraints();
        l.setMinWidth(140);
        ColumnConstraints f = new ColumnConstraints();
        f.setHgrow(Priority.ALWAYS);
        f.setFillWidth(true);
        caGrid.getColumnConstraints().addAll(l, f, new ColumnConstraints(), new ColumnConstraints());
        Label caLabel = fieldLabel("settings.network.ca");
        caLabel.setWrapText(true);
        caLabel.setMaxWidth(140);
        caGrid.add(caLabel, 0, 0);
        caGrid.add(tfCa, 1, 0);
        caGrid.add(btnBrowse, 2, 0);
        caGrid.add(btnRemove, 3, 0);
        caStatus.setFont(Font.font("System", 11));
        caStatus.setWrapText(true);
        caGrid.add(caStatus, 1, 1, 3, 1);
        caGrid.add(hint(t("settings.network.ca.hint")), 1, 2, 3, 1);

        // Test.
        btnTest.setId("network-test");
        btnTest.setFont(Font.font("System", FontWeight.BOLD, 12));
        btnTest.setPadding(new Insets(6, 14, 6, 14));
        btnTest.setStyle("-fx-background-color: transparent; -fx-text-fill: " + BLUE + ";"
            + "-fx-border-color: " + BLUE + "; -fx-border-radius: 6; -fx-background-radius: 6; -fx-cursor: hand;");
        btnTest.setOnAction(e -> runTest());
        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);
        Button btnCopy = flatButton(t("settings.network.copy"), BLUE);
        btnCopy.setId("network-copy-for-it");
        btnCopy.setTooltip(hintTooltip(t("settings.network.copy.hint")));
        btnCopy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(detailsForIt());
            Clipboard.getSystemClipboard().setContent(cc);
            btnCopy.setText(t("settings.network.copied"));
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox testRow = new HBox(10, btnTest, spinner, gap, btnCopy);
        testRow.setAlignment(Pos.CENTER_LEFT);
        results.setId("network-results");

        VBox box = new VBox(10,
            intro,
            subTitle(t("settings.network.proxy")),
            rbSystem, detected, rbNone, rbManual, manualGrid,
            subTitle(t("settings.network.certificates")),
            chkSystemStore, storeStatus, caGrid,
            subTitle(t("settings.network.testtitle")),
            testRow, hint(t("settings.network.test.hint")), results);
        box.setId("settings-network");
        box.setPadding(new Insets(4, 20, 12, 20));
        return box;
    }

    private void fill(NetworkSettings s) {
        (switch (s.proxyMode()) { case NONE -> rbNone; case MANUAL -> rbManual; default -> rbSystem; }).setSelected(true);
        tfHost.setText(s.proxyHost() != null ? s.proxyHost() : "");
        tfPort.setText(s.proxyPort() > 0 ? String.valueOf(s.proxyPort()) : "");
        tfUser.setText(s.proxyUser() != null ? s.proxyUser() : "");
        pfPassword.clear();
        pfPassword.setPromptText(s.proxyPassword() != null ? t("settings.network.password.saved") : "");
        chkSystemStore.setSelected(s.trustSystemStore());
        tfCa.setText(s.institutionCa() != null ? s.institutionCa().toString() : "");
        refreshMode();
        refreshCa();
        results.getChildren().clear();
        lastReport = null;
        readComputerAsync();
    }

    /** What a new installation uses (Reset all to default). */
    void reset() { fill(NetworkSettings.defaults()); }

    private void refreshMode() {
        manualGrid.setDisable(!rbManual.isSelected());
    }

    /** The proxy and the certificate store of this computer, read off the FX thread. */
    private void readComputerAsync() {
        Thread.ofVirtual().name("qtrace-network-read").start(() -> {
            String proxyLine = describeSystemProxy();
            TrustStores.SystemStore store = TrustStores.systemStore();
            String storeLine = store.available()
                ? t("settings.network.store.count", store.name(), store.certificates().size())
                : t("settings.network.store.none", store.error() != null ? store.error() : store.name());
            Platform.runLater(() -> {
                detected.setText(proxyLine);
                storeStatus.setText(storeLine);
            });
        });
    }

    private static String describeSystemProxy() {
        Proxy jvm = jvmProxy();
        if (jvm != null) return t("settings.network.detected.jvm", jvm.address());
        SystemProxy.Config c = SystemProxy.current();
        SystemProxy.HostPort p = c.proxyFor(PROBE);
        if (p != null) return t("settings.network.detected", c.source(), p);
        if (c.hasAutomaticScript()) return t("settings.network.detected.pac", c.pacUrl() != null ? c.pacUrl() : "WPAD");
        return t("settings.network.detected.none");
    }

    private static Proxy jvmProxy() {
        try {
            var sel = java.net.ProxySelector.getDefault();
            if (sel == null) return null;
            for (Proxy p : sel.select(PROBE)) if (p.type() == Proxy.Type.HTTP) return p;
        } catch (RuntimeException ignored) { }
        return null;
    }

    // ── Certificate file ───────────────────────────────────────────────────────

    private void chooseCa() {
        FileChooser fc = new FileChooser();
        fc.setTitle(t("settings.network.ca.chooser"));
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter(t("settings.network.ca.filter"), "*.pem", "*.crt", "*.cer", "*.der"),
            new FileChooser.ExtensionFilter("*", "*"));
        File chosen = fc.showOpenDialog(owner);
        if (chosen != null) {
            tfCa.setText(chosen.getAbsolutePath());
            refreshCa();
        }
    }

    private void refreshCa() {
        String path = tfCa.getText().strip();
        if (path.isEmpty()) {
            caStatus.setText("");
            caStatus.setManaged(false);
            return;
        }
        caStatus.setManaged(true);
        try {
            List<X509Certificate> certs = TrustStores.readCertificates(Path.of(path));
            X509Certificate first = certs.get(0);
            String name = TrustStores.shortName(first.getSubjectX500Principal());
            if (first.getBasicConstraints() < 0 && !first.getSubjectX500Principal().equals(first.getIssuerX500Principal())) {
                status(t("settings.network.ca.notca", name), ORANGE);
            } else if (first.getNotAfter().getTime() < System.currentTimeMillis()) {
                status(t("settings.network.ca.expired", name, DAY.format(first.getNotAfter().toInstant())), ORANGE);
            } else if (certs.size() > 1) {
                status(t("settings.network.ca.more", name, certs.size() - 1, DAY.format(first.getNotAfter().toInstant())), GREEN);
            } else {
                status(t("settings.network.ca.ok", name, DAY.format(first.getNotAfter().toInstant())), GREEN);
            }
        } catch (IOException | RuntimeException e) {
            status(t("settings.network.ca.bad", Path.of(path).getFileName()), RED);
        }
    }

    private void status(String text, String color) {
        caStatus.setText(text);
        Theme.text(caStatus, color);
    }

    // ── What the form says ─────────────────────────────────────────────────────

    /** The settings on screen, the saved password kept when the field is left empty. */
    NetworkSettings formSettings() {
        NetworkSettings.ProxyMode mode = rbManual.isSelected() ? NetworkSettings.ProxyMode.MANUAL
            : rbNone.isSelected() ? NetworkSettings.ProxyMode.NONE : NetworkSettings.ProxyMode.SYSTEM;
        int port;
        try { port = Integer.parseInt(tfPort.getText().strip()); } catch (NumberFormatException e) { port = 0; }
        String password = pfPassword.getText().isEmpty() ? saved.proxyPassword() : pfPassword.getText();
        String ca = tfCa.getText().strip();
        return new NetworkSettings(mode, tfHost.getText(), port, tfUser.getText(), password,
            chkSystemStore.isSelected(), ca.isEmpty() ? null : Path.of(ca));
    }

    /** Empty when the form can be saved; otherwise the message to show. */
    String problem() {
        NetworkSettings s = formSettings();
        if (!s.problem().isEmpty()) return t(s.problem());
        if (s.institutionCa() != null) {
            try {
                TrustStores.readCertificates(s.institutionCa());
            } catch (IOException e) {
                return t("settings.network.ca.bad", s.institutionCa().getFileName());
            }
        }
        return "";
    }

    /** Writes the form into {@code cfg} (the caller saves it) and makes it the network in force. */
    void save(QTraceConfig cfg) throws IOException {
        NetworkSettings s = formSettings();
        Path dir = Account.defaultDir();
        if (s.institutionCa() != null) s = s.withInstitutionCa(NetworkSettingsStore.importInstitutionCa(s.institutionCa(), dir));
        NetworkSettingsStore.store(s, cfg, dir);
        QTraceHttp.apply(s);
    }

    // ── Test ───────────────────────────────────────────────────────────────────

    private void runTest() {
        NetworkSettings s = formSettings();
        btnTest.setDisable(true);
        spinner.setVisible(true);
        results.getChildren().setAll(muted(t("settings.network.testing")));
        Thread.ofVirtual().name("qtrace-network-test").start(() -> {
            ConnectionTest.Report report = ConnectionTest.run(s, ConnectionTest.targets(SERVER));
            Platform.runLater(() -> {
                lastReport = report;
                showReport(report);
                btnTest.setDisable(false);
                spinner.setVisible(false);
                readComputerAsync();
            });
        });
    }

    private void showReport(ConnectionTest.Report report) {
        results.getChildren().clear();
        for (ConnectionTest.Outcome o : report.outcomes()) {
            Label mark = new Label(o.ok() ? "✓" : "✗");
            Theme.text(mark, o.ok() ? GREEN : RED);
            mark.setFont(Font.font("System", FontWeight.BOLD, 12));
            mark.setMinWidth(14);
            Label what = new Label(t(o.target().purposeKey()) + "  ·  " + o.target().host());
            Theme.text(what, TEXT_MAIN);
            what.setFont(Font.font("System", FontWeight.BOLD, 11));
            Label msg = new Label(o.message());
            Theme.text(msg, o.ok() ? TEXT_SUB : TEXT_MAIN);
            msg.setFont(Font.font("System", 11));
            msg.setWrapText(true);
            VBox text = new VBox(2, what, msg);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(8, mark, text);
            row.setAlignment(Pos.TOP_LEFT);
            results.getChildren().add(row);
        }
        if (report.hint() != null) {
            Label hint = new Label(report.hint());
            hint.setWrapText(true);
            hint.setFont(Font.font("System", 11));
            Theme.text(hint, TEXT_SUB);
            hint.setMaxWidth(Double.MAX_VALUE);
            hint.setPadding(new Insets(8, 10, 8, 10));
            hint.setStyle("-fx-background-color: " + BG_SURFACE + "; -fx-background-radius: 6;"
                + "-fx-border-color: " + ORANGE + "; -fx-border-radius: 6;");
            results.getChildren().add(hint);
        }
    }

    /** A plain-text summary for the IT department: what qTrace needs, and what the last test saw. */
    String detailsForIt() {
        NetworkSettings s = formSettings();
        StringBuilder b = new StringBuilder();
        b.append(t("network.it.title")).append("\n\n");
        b.append(t("network.it.rule")).append('\n');
        b.append("- www.qtrace.ca, qtrace.ca: ").append(t("network.target.server")).append('\n');
        b.append("- ").append(QTraceHttp.STORAGE_HOST).append(": ").append(t("network.target.storage")).append('\n');
        b.append("- github.com, release-assets.githubusercontent.com, objects.githubusercontent.com: ")
         .append(t("network.target.github")).append('\n');
        b.append('\n').append(t("network.it.proxy")).append('\n');
        b.append(t("network.it.inspection")).append("\n\n");
        Proxy route = QTraceHttp.routeFor(s, PROBE);
        b.append(t("network.it.settings", s.proxyMode().id(),
            route.type() == Proxy.Type.HTTP ? route.address() : t("network.via.direct"),
            s.institutionCa() != null ? s.institutionCa().getFileName() : "-")).append('\n');
        if (lastReport != null) {
            b.append('\n').append(t("network.it.last")).append('\n');
            for (ConnectionTest.Outcome o : lastReport.outcomes())
                b.append(o.ok() ? "[OK] " : "[FAILED] ").append(o.target().host()).append(": ").append(o.message()).append('\n');
        }
        b.append('\n').append("qTrace ").append(QTraceController.VERSION).append(" · Java ")
         .append(System.getProperty("java.version")).append(" · ").append(System.getProperty("os.name"))
         .append(' ').append(System.getProperty("os.version", "")).append('\n');
        return b.toString();
    }

    // ── Small builders ─────────────────────────────────────────────────────────

    private static RadioButton radio(String key, String id) {
        RadioButton rb = new RadioButton(t(key));
        rb.setId(id);
        Theme.text(rb, TEXT_SUB);
        rb.setWrapText(true);
        return rb;
    }

    private static Label fieldLabel(String key) {
        Label l = new Label(t(key));
        Theme.text(l, TEXT_SUB);
        l.setFont(Font.font("System", FontWeight.NORMAL, 12));
        return l;
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        Theme.text(l, TEXT_MUTED);
        l.setFont(Font.font("System", 11));
        l.setWrapText(true);
        return l;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        Theme.text(l, TEXT_MUTED);
        l.setFont(Font.font("System", 10));
        l.setWrapText(true);
        return l;
    }
}
