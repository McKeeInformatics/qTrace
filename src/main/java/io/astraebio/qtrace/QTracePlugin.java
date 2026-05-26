package io.astraebio.qtrace;

import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;
import java.util.Optional;

/**
 * Service interface implemented by the Enterprise module.
 * Discovered at runtime via ServiceLoader — if no enterprise JAR is present,
 * QTracePluginManager.get() returns null and premium features are silently absent.
 */
public interface QTracePlugin {
    void showDashboard(QuPathGUI qupath);
    Optional<ValidationStamp> showValidationDialog(Stage stage, String gitHash,
                                                   String imageHash, ClassifierFidelity fidelity);
    void startBatch(QuPathGUI qupath, QTraceController controller);
}
