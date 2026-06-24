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

package io.astraebio.qtrace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import qupath.lib.gui.QuPathGUI;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves whether the Enterprise edition is licensed & active at startup, and
 * downgrades to Core behaviour (no certification / compliance) when it is not.
 *
 * Two signals, in order of certainty:
 *  1. Offline — no license configured, or the loaded .qtlicense JWT is expired
 *     ({@code getActiveLicenseInfo()} returns null). Certain without network.
 *  2. Online — {@code GET /api/license/status} reports {@code active:false}
 *     (covers a cancelled subscription while the JWT is still within its window).
 *
 * A network error / offline state never downgrades — Enterprise stays active so an
 * offline pathologist keeps certification. When inactive, the user is told once at
 * startup (with a link to the portal) and Enterprise features are gated off via
 * {@link QTracePluginManager#setEntitled(boolean)}.
 */
public final class QTraceLicenseGate {

    private QTraceLicenseGate() {}

    private static final String STATUS_URL = "https://qtrace.ca/api/license/status";
    private static final String PORTAL_URL = "https://qtrace.ca/portal";

    public static void checkAtStartup(QuPathGUI qupath, QTraceController controller) {
        QTracePlugin ep = QTracePluginManager.get();
        if (ep == null) return; // Core install — nothing to gate

        String licensePath = QTraceConfig.get().getLicensePath();
        if (licensePath == null || licensePath.isBlank()) {
            // No license configured yet — gate features but don't nag (setup state, not a renewal).
            QTracePluginManager.setEntitled(false);
            return;
        }

        String raw = readLicenseRaw(licensePath);
        if (raw == null) {
            // License file unreadable → can't trust identity (treat like a bad license).
            QTracePluginManager.setInactive("corrupted");
            notifyInvalid(qupath, controller);
            return;
        }

        // validateLicense() verifies the RS256 signature: null ⇒ invalid/tampered;
        // non-null ⇒ signature trusted (expiry is then a separate, softer state).
        LicenseInfo li = ep.validateLicense(raw);
        if (li == null) {
            // Corrupted / invalid signature → identity can no longer be guaranteed (ERROR).
            QTracePluginManager.setInactive("corrupted");
            notifyInvalid(qupath, controller);
            return;
        }
        if (li.expired()) {
            // Signature valid but expired → soft downgrade (WARNING).
            QTracePluginManager.setInactive("expired");
            notifyInactive(qupath, controller,
                QTraceI18n.t("license.inactive.expired"), Alert.AlertType.WARNING);
            return;
        }

        // License present, signed and not expired → confirm with the server (async).
        CompletableFuture.runAsync(() -> {
            try {
                String jwt = QTraceUpdater.licenseJwt();
                if (jwt == null) return;
                byte[] body = QTraceUpdater.httpGetBytes(STATUS_URL, jwt);
                JsonObject st = JsonParser
                    .parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                boolean active = st.has("active") && st.get("active").getAsBoolean();
                if (!active) {
                    QTracePluginManager.setInactive("inactive");
                    notifyInactive(qupath, controller,
                        QTraceI18n.t("license.inactive.subscription"), Alert.AlertType.WARNING);
                }
            } catch (Exception ignored) {
                // offline / server error — keep Enterprise active (do not downgrade on uncertainty)
            }
        });
    }

    private static String readLicenseRaw(String path) {
        try {
            return Files.readString(Path.of(path)).strip();
        } catch (Exception e) {
            return null;
        }
    }

    /** Expired / cancelled — soft degradation to Core (amber WARNING). */
    private static void notifyInactive(QuPathGUI qupath, QTraceController controller,
                                       String header, Alert.AlertType type) {
        alertWithPortal(qupath, controller, type, header, QTraceI18n.t("license.inactive.body"));
    }

    /** Invalid / tampered signature — identity cannot be guaranteed (red ERROR). */
    private static void notifyInvalid(QuPathGUI qupath, QTraceController controller) {
        alertWithPortal(qupath, controller, Alert.AlertType.ERROR,
            QTraceI18n.t("license.invalid.header"), QTraceI18n.t("license.invalid.body"));
    }

    private static void alertWithPortal(QuPathGUI qupath, QTraceController controller,
                                        Alert.AlertType type, String header, String body) {
        Platform.runLater(() -> {
            Alert a = new Alert(type);
            a.setTitle(QTraceI18n.t("license.inactive.title"));
            a.setHeaderText(header);
            a.setContentText(body);

            ButtonType openPortal = new ButtonType(
                QTraceI18n.t("license.inactive.open"), ButtonBar.ButtonData.LEFT);
            a.getButtonTypes().setAll(openPortal, ButtonType.OK);
            if (qupath != null && qupath.getStage() != null) a.initOwner(qupath.getStage());

            Optional<ButtonType> res = a.showAndWait();
            if (res.isPresent() && res.get() == openPortal) browse(PORTAL_URL);

            // Reflect the downgrade in the panel if it is already open.
            if (controller != null) controller.refreshPanel();
        });
    }

    private static void browse(String url) {
        new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported())
                    java.awt.Desktop.getDesktop().browse(new URI(url));
            } catch (Exception ignored) {}
        }, "qtrace-portal-browse").start();
    }
}
