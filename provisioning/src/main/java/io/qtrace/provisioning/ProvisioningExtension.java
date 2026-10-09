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

import io.qtrace.Provisioners;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.nio.file.Path;

/**
 * Entry point of the provisioning module, started by the qTrace loader after Core
 * (Qtrace-Load-Order 30, loader.md § 7, § 17). It decides which modules are on the workstation
 * — the ones it is served, their updates, the ones to switch off — and signs the user in. Its
 * only dialogs are the update prompt and the restart offer; it has no welcome window: Getting started is the welcome module's window,
 * which asks this one through Core ({@link Provisioners}).
 */
public class ProvisioningExtension implements QuPathExtension {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningExtension.class);
    static final Path QTRACE_DIR = Path.of(System.getProperty("user.home"), ".qTrace");

    private static boolean installed;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (installed) return;
        installed = true;

        ProvisioningService service = new ProvisioningService(qupath, QTRACE_DIR);
        Provisioners.register(service);
        if (service.gettingStartedPending()) {
            log.info("[qtrace-provisioning] no license yet: Getting started is to be shown");
            // Getting started has the modules open to anyone installed: the startup check
            // does not ask for them over its window.
            ModuleInstaller.leaveOpenModulesToGettingStarted();
        }
        // What this workstation is served: Core switches off the modules that no longer are.
        ModuleInstaller.refreshServed();
        // Startup update check (async; the user validates, applied at the next start).
        ModuleInstaller.checkModules(qupath);
    }

    @Override
    public String getName() {
        return "qTrace Provisioning";
    }

    @Override
    public String getDescription() {
        return "Installs the qTrace modules of this workstation and signs in to qtrace.ca.";
    }
}
