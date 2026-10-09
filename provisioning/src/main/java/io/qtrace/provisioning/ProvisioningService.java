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

import io.qtrace.ModuleUpdates;
import io.qtrace.Provisioner;
import io.qtrace.QTraceConfig;
import io.qtrace.QTraceUpdater;
import javafx.application.Platform;
import qupath.lib.gui.QuPathGUI;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * What this module does for the welcome windows ({@link Provisioner}, loader.md § 17): it
 * installs the modules open to anyone, says what each module served here is, checks the
 * network and signs in. No window of its own.
 */
final class ProvisioningService implements Provisioner {

    // Same overrides as Core's updater, so the local simulator (tools/update-sim/) drives it too.
    static final String SERVER = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");
    private static final String GITHUB = System.getProperty("qtrace.loader.bootstrap",
        "https://github.com/RomainTourte/qTrace-core/releases/latest/download/qtrace-bootstrap.json");
    private static final int OPEN_MODULES_WAIT_S = 4;

    private final QuPathGUI qupath;
    private final Path qtraceDir;

    ProvisioningService(QuPathGUI qupath, Path qtraceDir) {
        this.qupath = qupath;
        this.qtraceDir = qtraceDir;
    }

    @Override
    public boolean gettingStartedPending() {
        return ProvisioningState.load(qtraceDir).shouldShowTrunk(QTraceConfig.get().getLicensePath());
    }

    @Override
    public void gettingStartedDone() {
        ProvisioningState.load(qtraceDir).markTrunkDone();
    }

    /**
     * The modules open to anyone (GET /api/modules/open) are asked for — a few seconds at most,
     * nothing offline — and the ones not on this workstation yet are installed without another
     * question nor a dialog: the window that asked offers the restart.
     */
    @Override
    public CompletableFuture<List<OpenModule>> provisionOpenModules() {
        CompletableFuture<List<OpenModule>> done = new CompletableFuture<>();
        CompletableFuture.supplyAsync(ProvisioningService::openModules)
            .completeOnTimeout(List.of(), OPEN_MODULES_WAIT_S, TimeUnit.SECONDS)
            .exceptionally(e -> List.of())
            .thenAccept(modules -> Platform.runLater(() -> {
                if (modules.stream().anyMatch(OpenModule::installing)) ModuleInstaller.installModulesNow(qupath, false);
                done.complete(modules);
            }));
        return done;
    }

    /** What the server opens to anyone, with whether each is still to be installed here. */
    private static List<OpenModule> openModules() {
        Map<String, String> local = ModuleUpdates.localVersions(QTraceUpdater.extensionsDir(QTraceUpdater.class));
        List<ModuleUpdates.Card> open = ModuleInstaller.openModules();
        List<ModuleUpdates.Offer> pending = ModuleUpdates.pending(
            open.stream().map(ModuleUpdates.Card::offer).toList(), local);
        return open.stream().map(m -> new OpenModule(m.offer().module(), m.title(),
            pending.contains(m.offer()))).toList();
    }

    @Override
    public List<ModuleUpdates.Card> moduleCards() {
        return ModuleInstaller.moduleCards();
    }

    @Override
    public void checkNetwork(BiConsumer<String, Boolean> reached) {
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("qtrace.ca", SERVER + "/api/version");
        targets.put("github.com", GITHUB);
        targets.forEach((name, url) -> CompletableFuture.runAsync(() ->
            reached.accept(name, NetworkCheck.unreachable(Map.of(name, url)).isEmpty())));
    }

    @Override
    public void signIn(SignIn listener, BooleanSupplier cancelled, boolean quitDialog, String invite) {
        new SignInFlow(qupath, qtraceDir, SERVER).start(listener, cancelled, quitDialog, invite);
    }
}
