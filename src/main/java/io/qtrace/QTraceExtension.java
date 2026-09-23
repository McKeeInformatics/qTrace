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

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

import java.util.ServiceLoader;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the QTrace QuPath extension.
 *
 * Registered via ServiceLoader:
 *   META-INF/services/qupath.lib.gui.extensions.QuPathExtension
 *
 * Adds:
 *  - "Extensions > QTrace" menu
 *  - A toolbar MenuButton (icon + dropdown) with recording state indicator
 */
public class QTraceExtension implements QuPathExtension, GitHubProject {

    private static final String COLOR_IDLE      = "#6c7086"; // Catppuccin TEXT_MUTED — no image
    private static final String COLOR_RECORDING = "#f38ba8"; // Catppuccin RED — recording active

    // Guards against a double install if an update left two qtrace-core JARs on the
    // classpath for one restart (QuPath instantiates one extension per service entry).
    private static boolean installed = false;

    private static final Logger log = LoggerFactory.getLogger(QTraceExtension.class);

    private QTraceController controller;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) return;
        installed = true;

        // Best-effort cleanup of superseded JARs left by a previous auto-update — only for a
        // direct install; under the qTrace loader, the loader keeps/cleans module versions.
        if (!QTraceUpdater.loaderMode()) {
            QTraceUpdater.reapOldJars(QTraceExtension.class, "compliance");
            QTraceUpdater.reapOldJars(QTraceExtension.class, "core");
        }

        // Register the highest-version QTracePlugin among those discovered — an update
        // may leave two Compliance JARs loaded until the old file is reaped next restart.
        QTracePlugin best = null;
        for (QTracePlugin p : ServiceLoader.load(QTracePlugin.class, QTracePlugin.class.getClassLoader())) {
            if (best == null || QTraceUpdater.compareSemver(
                    nz(p.getPluginVersion()), nz(best.getPluginVersion())) > 0) {
                best = p;
            }
        }
        if (best != null) QTracePluginManager.register(best);
        logClassLoaderDiagnostic(best);

        controller = new QTraceController(qupath);
        // Deferred like the toolbar button below — the File menu's items aren't
        // guaranteed to be fully populated until QuPath finishes its own layout.
        Platform.runLater(() -> controller.hookImportObjectsMenuItem(qupath));

        // ── Extensions menu ────────────────────────────────────────────────────
        var menu = qupath.getMenu("Extensions>QTrace", true);

        MenuItem openPanel   = new MenuItem("Open QTrace Panel");
        openPanel.setOnAction(e -> controller.showPanel());

        MenuItem dashboard   = new MenuItem("Dashboard");
        dashboard.setOnAction(e -> controller.showDashboard());

        // Compliance-only (no-ops via openReplayDialog() when not entitled — same
        // safety net as the panel's Replay button, see QTracePanel.java:296).
        MenuItem player      = new MenuItem("Player");
        player.setOnAction(e -> controller.openReplayDialog());

        MenuItem preferences = new MenuItem("Preferences...");
        preferences.setOnAction(e -> controller.showPreferences());

        MenuItem about       = new MenuItem("About QTrace...");
        about.setOnAction(e -> controller.showAbout());

        MenuItem report      = new MenuItem(QTraceI18n.t("report.menu"));
        report.setOnAction(e -> IssueReportDialog.show(qupath));

        menu.getItems().addAll(openPanel, dashboard, player, new SeparatorMenuItem(), preferences, report, about);

        // ── Toolbar button (added on FX thread after QuPath finishes layout) ───
        Platform.runLater(() -> addToolbarButton(qupath));

        // ── License entitlement (offline-certain part runs synchronously) ─────
        // Downgrades Compliance → Core when the license is expired/inactive, before
        // the panel is ever shown; the server confirmation runs async.
        QTraceLicenseGate.checkAtStartup(qupath, controller);

        // ── Startup update checks (async; user-validated, applied on restart) ──
        // Both checks are Core-driven so an old Compliance JAR still gets updated.
        if (QTraceUpdater.loaderMode()) {
            QTraceUpdater.checkModules(qupath);
        } else {
            QTraceUpdater.checkCore(qupath);
            QTraceUpdater.checkCompliance(qupath, QTracePluginManager.get());
        }
    }

    private static String nz(String s) { return s != null ? s : "0"; }

    /**
     * Startup trace of what the classloader REALLY loaded: which JAR file each qTrace
     * entry class came from, the version its own manifest declares vs the version
     * reported at runtime, and every qTrace service descriptor visible (duplicates =
     * several qTrace JARs on the classpath). Diagnoses update problems on any OS.
     */
    private static void logClassLoaderDiagnostic(QTracePlugin plugin) {
        try {
            logOrigin("core", QTraceExtension.class, QTraceController.VERSION);
            if (plugin != null) logOrigin("plugin", plugin.getClass(), plugin.getPluginVersion());
            var urls = QTraceExtension.class.getClassLoader()
                .getResources("META-INF/services/io.qtrace.QTracePlugin");
            while (urls.hasMoreElements())
                log.info("[qtrace-update] diag: plugin descriptor visible at {}", urls.nextElement());
            var ext = QTraceExtension.class.getClassLoader()
                .getResources("io/qtrace/QTraceExtension.class");
            while (ext.hasMoreElements())
                log.info("[qtrace-update] diag: core class visible at {}", ext.nextElement());
        } catch (Exception e) {
            log.warn("[qtrace-update] diag failed: {}", e.toString());
        }
    }

    private static void logOrigin(String what, Class<?> c, String reported) {
        String jar = "?", manifestVer = "?";
        try {
            var src = c.getProtectionDomain().getCodeSource();
            if (src != null) {
                var p = java.nio.file.Path.of(src.getLocation().toURI());
                jar = p.toString();
                if (java.nio.file.Files.isRegularFile(p)) {
                    try (JarFile jf = new JarFile(p.toFile())) {
                        var mf = jf.getManifest();
                        if (mf != null) manifestVer = mf.getMainAttributes().getValue("Implementation-Version");
                    }
                }
            }
        } catch (Exception e) {
            manifestVer = "unreadable (" + e + ")";
        }
        log.info("[qtrace-update] diag: {} {} loaded from {} | manifest version={} | reported version={} | package version={}",
            what, c.getName(), jar, manifestVer, reported, c.getPackage().getImplementationVersion());
    }

    private void addToolbarButton(QuPathGUI qupath) {
        // ── Icon: logo over a coloured rectangle ───────────────────────────────
        Rectangle bg = new Rectangle(22, 22);
        bg.setArcWidth(4);
        bg.setArcHeight(4);
        bg.setFill(Color.web(COLOR_IDLE));

        Image logoImg = QTracePanel.loadLogo();
        ImageView logoView = new ImageView();
        if (logoImg != null) {
            logoView.setImage(logoImg);
            logoView.setFitHeight(16);
            logoView.setPreserveRatio(true);
        }

        StackPane icon = new StackPane(bg, logoView);
        icon.setAlignment(Pos.CENTER);
        icon.setPadding(new Insets(2));

        // ── MenuButton ─────────────────────────────────────────────────────────
        MenuButton btn = new MenuButton();
        btn.setGraphic(icon);
        btn.setText("");
        btn.setTooltip(new Tooltip("qTrace — Workflow Provenance"));
        btn.setStyle(
            "-fx-background-color: transparent;"
          + "-fx-padding: 2 4 2 4;"
          + "-fx-cursor: hand;"
        );

        MenuItem miPanel  = new MenuItem("Panel");
        miPanel.setOnAction(e -> controller.showPanel());

        MenuItem miDash   = new MenuItem("Dashboard");
        miDash.setOnAction(e -> controller.showDashboard());

        // Compliance-only (no-ops via openReplayDialog() when not entitled — same
        // safety net as the panel's Replay button, see QTracePanel.java:296).
        MenuItem miPlayer = new MenuItem("Player");
        miPlayer.setOnAction(e -> controller.openReplayDialog());

        MenuItem miPrefs  = new MenuItem("Preferences...");
        miPrefs.setOnAction(e -> controller.showPreferences());

        MenuItem miAbout  = new MenuItem("About qTrace...");
        miAbout.setOnAction(e -> controller.showAbout());

        MenuItem miReport = new MenuItem(QTraceI18n.t("report.menu"));
        miReport.setOnAction(e -> IssueReportDialog.show(qupath));

        btn.getItems().addAll(miPanel, miDash, miPlayer, new SeparatorMenuItem(), miPrefs, miReport, miAbout);

        // ── Dynamic icon colour based on recording state ───────────────────────
        controller.addRecordingListener(recording ->
            bg.setFill(Color.web(recording ? COLOR_RECORDING : COLOR_IDLE))
        );
        // Sync initial state
        bg.setFill(Color.web(controller.isRecording() ? COLOR_RECORDING : COLOR_IDLE));

        qupath.getToolBar().getItems().add(btn);
    }

    @Override
    public String getName() {
        return "QTrace — Workflow Provenance & Certification";
    }

    @Override
    public String getDescription() {
        return "Captures QuPath workflow history and certifies analyses with expert validation stamps.";
    }

    /** Lets QuPath's native update checker track the public Core repo. */
    @Override
    public GitHubProject.GitHubRepo getRepository() {
        return GitHubProject.GitHubRepo.create(
            "qTrace — Workflow Provenance & Certification", "RomainTourte", "qTrace-core");
    }
}
