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

    private static final String STATUS_URL = "https://q-trace-alpha.vercel.app/api/license/status";
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

        LicenseInfo li = ep.getActiveLicenseInfo(); // null if expired/invalid (offline-certain)
        if (li == null) {
            QTracePluginManager.setEntitled(false);
            notifyInactive(qupath, controller, QTraceI18n.t("license.inactive.expired"));
            return;
        }

        // License present and not expired offline → confirm with the server (async).
        CompletableFuture.runAsync(() -> {
            try {
                String jwt = QTraceUpdater.licenseJwt();
                if (jwt == null) return;
                byte[] body = QTraceUpdater.httpGetBytes(STATUS_URL, jwt);
                JsonObject st = JsonParser
                    .parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                boolean active = st.has("active") && st.get("active").getAsBoolean();
                if (!active) {
                    QTracePluginManager.setEntitled(false);
                    notifyInactive(qupath, controller, QTraceI18n.t("license.inactive.subscription"));
                }
            } catch (Exception ignored) {
                // offline / server error — keep Enterprise active (do not downgrade on uncertainty)
            }
        });
    }

    private static void notifyInactive(QuPathGUI qupath, QTraceController controller, String reason) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.WARNING);
            a.setTitle(QTraceI18n.t("license.inactive.title"));
            a.setHeaderText(reason);
            a.setContentText(QTraceI18n.t("license.inactive.body"));

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
