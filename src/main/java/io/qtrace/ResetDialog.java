package io.qtrace;

import java.util.Optional;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Alert;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** The one question Reset asks: how far to go. Reset is destructive, so Cancel is the default. */
final class ResetDialog {

    private ResetDialog() {}

    /** The ack is needed when stamped sessions would be set aside. */
    static boolean needsAcknowledgement(ResetLevel level, int stamped) {
        return level.setsRecordAside() && stamped > 0;
    }

    static Optional<ResetLevel> ask(Window owner, int sessions, int stamped) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (owner != null) alert.initOwner(owner);
        alert.setTitle("qTrace — Reset");
        ButtonType reset  = new ButtonType("Reset", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE); // Esc
        alert.getButtonTypes().setAll(cancel, reset);

        ToggleGroup group = new ToggleGroup();
        RadioButton capture = radio(group, ResetLevel.CAPTURE, "Capture only",
            "Clears what qTrace recorded. The image's workflow history and its .qtrace stay: they come back when you reopen the image.");
        RadioButton image = radio(group, ResetLevel.IMAGE, "Capture and the image's history",
            "Also empties the image's own workflow history (saved with the image) and qTrace's memory of classifiers and imports. Its .qtrace stays.");
        String record = sessions == 0 ? "This image has no .qtrace yet."
            : "Its .qtrace (" + sessions + " session" + (sessions == 1 ? "" : "s")
              + (stamped > 0 ? ", " + stamped + " stamped" : "") + ") is renamed, never deleted.";
        RadioButton everything = radio(group, ResetLevel.EVERYTHING, "Everything - start from zero",
            "Also sets the .qtrace aside, so the next stamp starts a new chain and nothing of the past is replayed. " + record
            + " The project's Git history is kept.");
        everything.setSelected(true);

        CheckBox ack = new CheckBox("I understand: the stamped sessions leave this image's record");
        VBox body = new VBox(12, capture, image, everything, ack);

        DialogLook.apply(alert, "Reset this image", "How far back should it go?", body,
            reset, DialogLook.RED, cancel);
        if (alert.getDialogPane().lookupButton(reset) instanceof Button b) {
            b.setDefaultButton(false);
            Runnable sync = () -> {
                ResetLevel l = (ResetLevel) group.getSelectedToggle().getUserData();
                boolean need = needsAcknowledgement(l, stamped);
                ack.setVisible(need);
                ack.setManaged(need);
                b.setDisable(need && !ack.isSelected());
            };
            group.selectedToggleProperty().addListener((o, a, n) -> sync.run());
            ack.selectedProperty().addListener((o, a, n) -> sync.run());
            sync.run();
        }
        if (alert.getDialogPane().lookupButton(cancel) instanceof Button c) c.setDefaultButton(true);

        return alert.showAndWait().filter(bt -> bt == reset)
            .map(bt -> (ResetLevel) group.getSelectedToggle().getUserData());
    }

    private static RadioButton radio(ToggleGroup g, ResetLevel level, String title, String detail) {
        RadioButton r = new RadioButton(title + "\n" + detail);
        r.setToggleGroup(g);
        r.setUserData(level);
        r.setWrapText(true);
        r.setMaxWidth(420);
        return r;
    }
}
