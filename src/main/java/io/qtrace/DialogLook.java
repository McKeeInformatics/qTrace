package io.qtrace;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * The qTrace look for a JavaFX {@link Dialog} — the one Settings set: dark base, title bar on
 * the darker surface, a discreet Cancel and one solid action button. Esc cancels (the dialog
 * must carry a {@link ButtonType#CANCEL}-like button).
 */
public final class DialogLook {

    // Catppuccin Mocha — matches QTraceSettingsDialog / QTracePanel
    public static final String BG_BASE    = "-qt-bg-base";
    public static final String BG_SURFACE = "-qt-bg-surface";
    public static final String TEXT_MAIN  = "-qt-text-main";
    public static final String TEXT_SUB   = "-qt-text-sub";
    public static final String BLUE       = "-qt-blue";
    public static final String RED        = "-qt-red";

    private DialogLook() {}

    /**
     * @param body        what goes under the title bar (may be null)
     * @param action      the button that carries the action; restyled solid in {@code actionColor}
     * @param cancel      the cancel button; restyled as plain text
     */
    public static void apply(Dialog<?> dialog, String title, String subtitle, Node body,
                      ButtonType action, String actionColor, ButtonType cancel) {
        dialog.setHeaderText(null);
        dialog.setGraphic(null);

        Label titleLbl = new Label(title);
        titleLbl.setStyle("-fx-text-fill: " + TEXT_MAIN + "; -fx-font-size: 13; -fx-font-weight: bold;");
        VBox header = new VBox(2, titleLbl);
        if (subtitle != null && !subtitle.isBlank()) {
            Label sub = new Label(subtitle);
            sub.setWrapText(true);
            sub.setStyle("-fx-text-fill: " + TEXT_SUB + "; -fx-font-size: 11;");
            header.getChildren().add(sub);
        }
        header.setPadding(new Insets(12, 20, 12, 20));
        header.setStyle("-fx-background-color: " + BG_SURFACE + ";");

        DialogPane pane = dialog.getDialogPane();
        VBox content = new VBox(header);
        if (body != null) content.getChildren().add(body);
        pane.setContent(content);
        pane.setPadding(Insets.EMPTY);
        // -fx-base makes every control dark with light text.
        pane.setStyle("-fx-background-color: " + BG_BASE + "; -fx-base: " + BG_BASE + ";"
            + "-fx-control-inner-background: " + BG_SURFACE + "; -fx-accent: " + BLUE + ";"
            + "-fx-focus-color: " + BLUE + "; -fx-faint-focus-color: transparent;");

        if (pane.lookupButton(cancel) instanceof Button c)
            c.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;"
                + "-fx-text-fill: " + TEXT_SUB + "; -fx-cursor: hand; -fx-font-size: 12;");
        if (pane.lookupButton(action) instanceof Button a)
            a.setStyle("-fx-background-color: " + actionColor + "; -fx-text-fill: " + BG_BASE + ";"
                + "-fx-background-radius: 6; -fx-cursor: hand; -fx-font-size: 12; -fx-font-weight: bold;"
                + "-fx-padding: 6 18 6 18;");
    }

    /** A paragraph of body text, padded like the rest of the dialog. */
    public static Node text(String message) {
        Label l = new Label(message);
        l.setWrapText(true);
        l.setMaxWidth(380);
        l.setStyle("-fx-text-fill: " + TEXT_MAIN + "; -fx-font-size: 12;");
        VBox box = new VBox(l);
        box.setPadding(new Insets(16, 20, 6, 20));
        return box;
    }
}
