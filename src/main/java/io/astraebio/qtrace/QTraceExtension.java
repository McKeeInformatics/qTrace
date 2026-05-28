package io.astraebio.qtrace;

import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import java.util.ServiceLoader;

/**
 * Entry point for the QTrace QuPath extension.
 *
 * Registered via ServiceLoader:
 *   META-INF/services/qupath.lib.gui.extensions.QuPathExtension
 *
 * Adds an "Extensions > QTrace" menu with:
 *   - Open QTrace Panel  (main action)
 *   - About QTrace
 */
public class QTraceExtension implements QuPathExtension {

    private QTraceController controller;

    @Override
    public void installExtension(QuPathGUI qupath) {
        ServiceLoader.load(QTracePlugin.class, QTracePlugin.class.getClassLoader())
            .findFirst().ifPresent(QTracePluginManager::register);

        controller = new QTraceController(qupath);

        var menu = qupath.getMenu("Extensions>QTrace", true);

        MenuItem openPanel = new MenuItem("Open QTrace Panel");
        openPanel.setOnAction(e -> controller.showPanel());

        MenuItem dashboard = new MenuItem("Dashboard");
        dashboard.setOnAction(e -> controller.showDashboard());

        MenuItem preferences = new MenuItem("Preferences...");
        preferences.setOnAction(e -> controller.showPreferences());

        MenuItem about = new MenuItem("About QTrace...");
        about.setOnAction(e -> controller.showAbout());

        menu.getItems().addAll(openPanel, dashboard, new SeparatorMenuItem(), preferences, about);
    }

    @Override
    public String getName() {
        return "QTrace — Workflow Provenance & Certification";
    }

    @Override
    public String getDescription() {
        return "Captures QuPath workflow history, generates reproducible Groovy Meta-Scripts, "
             + "versions them in Git, and certifies analyses with expert validation stamps.";
    }
}
