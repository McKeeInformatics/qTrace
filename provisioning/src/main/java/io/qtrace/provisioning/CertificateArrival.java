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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.qtrace.Account;
import io.qtrace.QTraceConfig;
import io.qtrace.QTraceI18n;
import io.qtrace.QTraceUpdater;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The identity certificate of a workstation connected to an account arrives on its own. At
 * startup and every hour, while the workstation is connected to an account and has no licence,
 * ask {@code POST /api/account/certificate} (authenticated by the account token). When the portal
 * answers that the certificate exists, install it the way "Sign in" does, drop the account
 * token, install the modules the certificate gives and tell the user to restart QuPath once.
 * The decision ({@link #decide}) is pure; the effects are {@link #writeCertificate} (files) and
 * {@link #arrived} (window).
 */
final class CertificateArrival {

    private static final Logger log = LoggerFactory.getLogger(CertificateArrival.class);
    private static final String TAG = "[qtrace-certificate-arrival] ";
    private static final String SERVER = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");
    private static final AtomicBoolean scheduled = new AtomicBoolean();

    private CertificateArrival() {}

    // ── The decision (pure) ──────────────────────────────────────────────────────

    /** What to do with the portal's answer. */
    sealed interface Decision permits Install, Wait, Ignore {}

    /** The certificate exists: {@code envelopeJson} is the .qtlicense envelope to install. */
    record Install(String envelopeJson) implements Decision {}

    /** Not yet: {@code step} is what the portal says is still missing. */
    record Wait(String step) implements Decision {}

    /** Nothing readable, nothing to do. */
    record Ignore() implements Decision {}

    static final Decision IGNORE = new Ignore();

    static Decision decide(String answer) {
        try {
            JsonObject o = JsonParser.parseString(answer).getAsJsonObject();
            String status = text(o, "status");
            if ("ready".equals(status) && o.has("license") && o.get("license").isJsonObject()) {
                JsonObject license = o.getAsJsonObject("license");
                return text(license, "jwt").isBlank() ? IGNORE : new Install(license.toString());
            }
            if ("none".equals(status)) {
                String step = text(o, "step");
                return new Wait(step.isBlank() ? "unknown" : step);
            }
        } catch (RuntimeException e) {
            // null, empty, not JSON, not an object: nothing to do
        }
        return IGNORE;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /** Only a workstation connected to an account and without licence asks (an account never stands in for a licence). */
    static boolean shouldAsk(boolean hasLicence, boolean accountSignedIn) {
        return Account.counts(hasLicence, accountSignedIn);
    }

    // ── The effects ──────────────────────────────────────────────────────────────

    /**
     * Writes the certificate where "Sign in" writes it ({@link LicenseInstaller}) and removes the
     * account token: a workstation has a certificate or an account, not both. Returns the licence file.
     */
    static Path writeCertificate(String envelopeJson, Path qtraceDir) throws IOException {
        Path licence = LicenseInstaller.write(envelopeJson, qtraceDir);
        Files.deleteIfExists(qtraceDir.resolve(Account.FILE));
        Files.deleteIfExists(qtraceDir.resolve(Account.FILE + ".bak"));
        return licence;
    }

    /** POST /api/account/certificate with the account token; the body of the answer, or null when unreachable or refused. */
    static String ask(String server, String accountJwt) {
        try {
            String base = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/account/certificate"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accountJwt)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build();
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return r.statusCode() == 200 ? r.body() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Scheduling ───────────────────────────────────────────────────────────────

    /** Asks now, then every hour; stops once the certificate is installed or the account is no longer connected. */
    static void start(QuPathGUI qupath, Path qtraceDir) {
        if (!scheduled.compareAndSet(false, true)) return;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qtrace-certificate-arrival");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                String licence = QTraceConfig.get().getLicensePath();
                boolean hasLicence = licence != null && !licence.isBlank();
                if (!shouldAsk(hasLicence, Account.signedIn(qtraceDir, java.time.Instant.now()))) { ex.shutdown(); return; }
                if (askOnce(qupath, qtraceDir)) ex.shutdown();
            } catch (RuntimeException e) {
                log.info(TAG + "check skipped: {}", e.toString());
            }
        }, 0, 1, TimeUnit.HOURS);
    }

    /** One check; true when the certificate was installed. A network error or an unreadable answer changes nothing. */
    private static boolean askOnce(QuPathGUI qupath, Path qtraceDir) {
        String jwt = Account.jwt(qtraceDir);
        if (jwt == null) return false;
        Decision d = decide(ask(SERVER, jwt));
        if (d instanceof Wait w) {
            log.info(TAG + "no certificate yet, still missing: {}", w.step());
            return false;
        }
        if (!(d instanceof Install install)) return false;
        try {
            Path certificate = writeCertificate(install.envelopeJson(), qtraceDir);
            QTraceConfig.get().setLicensePath(certificate.toString());
            QTraceConfig.get().save();
            log.info(TAG + "certificate received and saved to {}", certificate);
        } catch (Exception e) {
            log.warn(TAG + "could not install the certificate", e);
            return false;
        }
        // The modules the certificate gives, without a dialog of their own: the notice below carries the restart.
        ModuleInstaller.installModulesNow(qupath, false).whenComplete((n, failed) -> arrived(qupath));
        return true;
    }

    /** Tells the user, without blocking anything: restart QuPath once. Later answers Esc. */
    private static void arrived(QuPathGUI qupath) {
        Platform.runLater(() -> QTraceUpdater.whenNoModalOpen("certificate notice", () ->
            ModuleInstaller.offerRestart(qupath,
                text("certificate.arrived.title", "Your identity certificate has arrived"),
                text("certificate.arrived.text", "qTrace installed it on this computer. Restart QuPath once: your next stamps will be certified."))));
    }

    /** A Core published before these texts shows the key: say it in English then. */
    private static String text(String key, String english) {
        String t = QTraceI18n.t(key);
        return key.equals(t) ? english : t;
    }
}
