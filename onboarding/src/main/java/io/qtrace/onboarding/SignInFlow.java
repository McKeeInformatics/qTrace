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
import io.qtrace.QTraceConfig;
import io.qtrace.QTraceUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * "Sign in to qtrace.ca", start to end, for either window (the HTML player or the plain
 * fallback dialog): device code → browser → certificate saved → licensed modules installed.
 * Reports each stage to a {@link Listener}, from a background thread.
 */
final class SignInFlow {

    private static final Logger log = LoggerFactory.getLogger(SignInFlow.class);
    private static final String TAG = "[qtrace-onboarding] ";

    interface Listener {
        void starting();
        void waiting(String userCode, String verificationUrl);
        void approved(Path certificate);
        void installing();
        void installed(int modules);
        void failed(String message);
    }

    private final QuPathGUI qupath;
    private final Path qtraceDir;
    private final String server;

    SignInFlow(QuPathGUI qupath, Path qtraceDir, String server) {
        this.qupath = qupath;
        this.qtraceDir = qtraceDir;
        this.server = server;
    }

    /** Runs in the background; {@code quitDialog=false} when the caller offers the restart. */
    void start(Listener l, BooleanSupplier cancelled, boolean quitDialog) {
        l.starting();
        DeviceFlowClient client = new DeviceFlowClient(server, Thread::sleep);
        CompletableFuture.runAsync(() -> {
            try {
                DeviceFlowClient.Start s = client.start();
                l.waiting(s.userCode(), s.verificationUrl());
                BrowserOpener.open(s.verificationUrl());
                String envelope = client.awaitLicense(s, cancelled);
                Path certificate = LicenseInstaller.write(envelope, qtraceDir);
                QTraceConfig.get().setLicensePath(certificate.toString());
                QTraceConfig.get().save();
                OnboardingState.load(qtraceDir).markTrunkDone();
                log.info(TAG + "certificate received and saved to {}", certificate);
                l.approved(certificate);
                l.installing();
                QTraceUpdater.installModulesNow(qupath, quitDialog).thenAccept(l::installed);
            } catch (DeviceFlowClient.DeviceFlowException e) {
                if (e.outcome() == DeviceFlowClient.Outcome.CANCELLED) return;
                log.info(TAG + "sign in ended: {} ({})", e.outcome(), e.getMessage());
                l.failed(switch (e.outcome()) {
                    case DENIED -> "The request was refused on qtrace.ca.";
                    case EXPIRED -> "The code expired before it was approved.";
                    default -> "Something went wrong: " + e.getMessage();
                });
            } catch (Exception e) {
                log.warn(TAG + "sign in failed", e);
                l.failed("Could not reach qtrace.ca (" + e.getMessage() + ").");
            }
        });
    }
}
