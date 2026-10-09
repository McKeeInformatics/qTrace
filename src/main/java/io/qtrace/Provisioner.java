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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * What the provisioning module does for the windows that present it (module welcome): it puts
 * on this workstation the modules it may have, and signs the user in to qtrace.ca. It shows
 * nothing itself — Getting started is the welcome module's window.
 */
public interface Provisioner {

    /** True on a workstation with no certificate whose user has not finished Getting started. */
    boolean gettingStartedPending();

    /** The user finished Getting started, or chose to go on without an account. */
    void gettingStartedDone();

    /**
     * Asks qtrace.ca which modules anyone may install and starts installing the ones that are
     * not here yet. Completes on the FX thread, with an empty list offline or after a few seconds.
     */
    CompletableFuture<List<OpenModule>> provisionOpenModules();

    /** Tells, one target at a time and from background threads, whether it could be reached. */
    void checkNetwork(BiConsumer<String, Boolean> reached);

    /**
     * "Sign in to qtrace.ca", start to end, in the background: device code, browser,
     * certificate saved, modules of the account installed.
     *
     * @param quitDialog false when the caller offers the restart itself
     * @param invite     the invitation code typed in QuPath, or null for an existing account
     */
    void signIn(SignIn listener, BooleanSupplier cancelled, boolean quitDialog, String invite);

    /**
     * A module anyone may install (GET /api/modules/open).
     *
     * @param installing true when it was not on this workstation: it is being installed and
     *                   starts at the next launch
     */
    record OpenModule(String name, String title, boolean installing) {}

    /** The stages of a sign-in, each reported from a background thread. */
    interface SignIn {
        void starting();
        void waiting(String userCode, String verificationUrl);
        void approved(Path certificate);
        void installing();
        void installed(int modules);
        void failed(String message);
    }
}
