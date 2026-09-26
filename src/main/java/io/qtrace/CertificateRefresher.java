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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A provisional identity certificate (identity not verified yet, loader.md § 17) is replaced by
 * the verified one without any action: at startup and every hour while provisional, ask
 * {@code GET /api/license/refresh}; a newer certificate for the same key has its jwt written into
 * the .qtlicense (the passphrase-encrypted key is unchanged) and the panel updates.
 */
public final class CertificateRefresher {

    private static final Logger log = LoggerFactory.getLogger(CertificateRefresher.class);
    private static final String TAG = "[qtrace-certificate] ";
    private static final String SERVER = System.getProperty("qtrace.update.server", "https://www.qtrace.ca");
    private static final AtomicBoolean scheduled = new AtomicBoolean();

    private CertificateRefresher() {}

    /** Checks now, then every hour for as long as the certificate stays provisional. */
    public static void start(QuPathGUI qupath, QTraceController controller) {
        if (!scheduled.compareAndSet(false, true)) return;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qtrace-certificate-refresh");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            QTracePlugin p = QTracePluginManager.get();
            LicenseInfo li = p != null ? p.getActiveLicenseInfo() : null;
            if (li != null && li.verified()) { ex.shutdown(); return; }
            refreshOnce(qupath, controller);
        }, 0, 1, TimeUnit.HOURS);
    }

    static void refreshOnce(QuPathGUI qupath, QTraceController controller) {
        try {
            String path = QTraceConfig.get().getLicensePath();
            if (path == null || path.isBlank()) return;
            Path file = Path.of(path);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String jwt = CertificateFile.jwtOf(content);
            if (jwt == null) return;
            JsonObject r = JsonParser.parseString(new String(
                QTraceUpdater.httpGetBytes(SERVER + "/api/license/refresh", jwt), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"renewed".equals(r.has("status") ? r.get("status").getAsString() : "")) return;
            Files.writeString(file, CertificateFile.replaceJwt(content, r.get("jwt").getAsString()), StandardCharsets.UTF_8);
            boolean verified = r.has("verified") && r.get("verified").getAsBoolean();
            log.info(TAG + "certificate renewed (verified={})", verified);
            Platform.runLater(() -> {
                if (controller != null) controller.refreshPanel();
                if (!verified) return;
                QTracePlugin p = QTracePluginManager.get();
                LicenseInfo li = p != null ? p.getActiveLicenseInfo() : null;
                Alert a = new Alert(Alert.AlertType.INFORMATION);
                a.setTitle("qTrace");
                a.setHeaderText("Your identity is verified");
                a.setContentText("Your results are now certified for " + (li != null ? li.name() : "you") + ".");
                if (qupath != null && qupath.getStage() != null) a.initOwner(qupath.getStage());
                a.show();
            });
        } catch (Exception e) {
            log.info(TAG + "refresh skipped: {}", e.toString());
        }
    }
}
