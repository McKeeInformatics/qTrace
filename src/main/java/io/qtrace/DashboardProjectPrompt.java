package io.qtrace;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard with "Use Project Folder" on and no project open: asks for a project — one click on
 * a recent one, or QuPath's own "Open Project…" — rather than showing whatever sits in the
 * configured fallback folder. Not modal, one at a time: the Dashboard button closes it again.
 */
final class DashboardProjectPrompt {

    private static final int MAX_RECENT = 6;

    /** A recent QuPath project that still exists on disk, named after its folder. */
    record Recent(URI uri, String name, String folder) {}

    /** What the user chose: a recent project, QuPath's file chooser, or nothing (cancelled). */
    record Choice(boolean cancelled, URI recent) {}

    private static Alert current; // FX thread only

    private DashboardProjectPrompt() {}

    /** Closes the prompt if it is open (= Cancel); true when it did. */
    static boolean closeIfOpen() {
        if (current == null || !current.isShowing()) return false;
        current.close();
        return true;
    }

    /** The first {@code max} local projects of {@code uris} (most recent first) that still exist. */
    static List<Recent> recents(List<URI> uris, int max) {
        List<Recent> out = new ArrayList<>();
        for (URI uri : uris) {
            if (out.size() >= max) break;
            try {
                if (!"file".equalsIgnoreCase(uri.getScheme())) continue;
                Path file = Path.of(uri);
                Path folder = file.getParent();
                if (folder == null || !Files.isRegularFile(file)) continue;
                out.add(new Recent(uri, folder.getFileName().toString(), folder.toString()));
            } catch (Exception ignored) {} // an unreadable entry is just not offered
        }
        return out;
    }

    static Choice show(QuPathGUI qupath) {
        Alert a = new Alert(Alert.AlertType.NONE);
        a.setTitle("qTrace — Dashboard");
        if (qupath.getStage() != null) a.initOwner(qupath.getStage());
        a.initModality(Modality.NONE);
        ButtonType open = new ButtonType(QTraceI18n.t("dashboard.project.open"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE); // Esc
        a.getButtonTypes().setAll(open, cancel);

        URI[] picked = new URI[1];
        VBox body = new VBox(10);
        body.setPadding(new Insets(16, 20, 6, 20));
        Label why = new Label(QTraceI18n.t("dashboard.project.content"));
        why.setWrapText(true);
        why.setMaxWidth(420);
        why.setStyle("-fx-text-fill: " + DialogLook.TEXT_SUB + "; -fx-font-size: 12;");
        body.getChildren().add(why);

        List<Recent> recents;
        try {
            recents = recents(new ArrayList<>(PathPrefs.getRecentProjectList()), MAX_RECENT);
        } catch (Throwable t) { // the preference isn't there in this QuPath: no list, the button remains
            recents = List.of();
        }
        if (!recents.isEmpty()) {
            Label heading = new Label(QTraceI18n.t("dashboard.project.recent"));
            heading.setStyle("-fx-text-fill: " + DialogLook.TEXT_MAIN + "; -fx-font-size: 12; -fx-font-weight: bold;");
            VBox list = new VBox(4);
            for (Recent r : recents) list.getChildren().add(row(r, () -> {
                picked[0] = r.uri();
                a.setResult(open);
                a.close();
            }));
            body.getChildren().addAll(heading, list);
        }

        DialogLook.apply(a, QTraceI18n.t("dashboard.project.header"), null, body, open, DialogLook.BLUE, cancel);
        current = a;
        try {
            ButtonType chosen = a.showAndWait().orElse(cancel);
            return new Choice(chosen != open, picked[0]);
        } finally {
            if (current == a) current = null;
        }
    }

    /** One clickable recent project: its name, and its folder underneath. */
    private static VBox row(Recent r, Runnable onClick) {
        Label name = new Label(r.name());
        name.setStyle("-fx-text-fill: " + DialogLook.TEXT_MAIN + "; -fx-font-size: 12;");
        Label folder = new Label(r.folder());
        folder.setStyle("-fx-text-fill: #6c7086; -fx-font-size: 10;");
        folder.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        folder.setMaxWidth(400);
        VBox row = new VBox(1, name, folder);
        row.setPadding(new Insets(6, 10, 6, 10));
        String idle = "-fx-background-color: " + DialogLook.BG_SURFACE + "; -fx-background-radius: 6;"
            + "-fx-border-color: #313244; -fx-border-radius: 6; -fx-cursor: hand;";
        String hover = "-fx-background-color: #26263a; -fx-background-radius: 6;"
            + "-fx-border-color: " + DialogLook.BLUE + "; -fx-border-radius: 6; -fx-cursor: hand;";
        row.setStyle(idle);
        row.setOnMouseEntered(e -> row.setStyle(hover));
        row.setOnMouseExited(e -> row.setStyle(idle));
        row.setOnMouseClicked(e -> onClick.run());
        return row;
    }
}
