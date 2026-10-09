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

import io.qtrace.QTraceConfig;
import io.qtrace.QTraceUpdater;
import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.nio.file.Path;

/**
 * Entry point of the provisioning module, started by the qTrace loader after Core
 * (Qtrace-Load-Order 30, loader.md § 17). Shows the trunk on a machine with no license until
 * the user signed in or chose Core only; always adds Extensions > QTrace > Getting started….
 */
public class ProvisioningExtension implements QuPathExtension {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningExtension.class);
    static final Path QTRACE_DIR = Path.of(System.getProperty("user.home"), ".qTrace");

    private static boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) return;
        installed = true;

        MenuItem item = new MenuItem("Getting started…");
        item.setOnAction(e -> new TrunkPlayer(qupath, QTRACE_DIR).show());
        qupath.getMenu("Extensions>QTrace", true).getItems().add(0, item);

        if (!ProvisioningState.load(QTRACE_DIR).shouldShowTrunk(QTraceConfig.get().getLicensePath())) return;
        log.info("[qtrace-provisioning] no license yet: showing Getting started");
        // Getting started installs the modules open to anyone and presents each of them:
        // Core's startup check does not ask for them over its window.
        QTraceUpdater.leaveOpenModulesToProvisioning();
        // After QuPath's own Welcome window, like Core's update prompts.
        Platform.runLater(() -> QTraceUpdater.whenNoModalOpen("provisioning",
            () -> new TrunkPlayer(qupath, QTRACE_DIR).show()));
    }

    @Override
    public String getName() {
        return "qTrace Provisioning";
    }

    @Override
    public String getDescription() {
        return "Getting started with qTrace: sign in to qtrace.ca and install your modules.";
    }
}
