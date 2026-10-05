package io.qtrace;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.util.Duration;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * The menu that unfolds from a panel button when modules extend it ({@link PanelExtensions}):
 * pointing at Dashboard or Version keeps the button lit and slides out the entries beside it
 * — under the button on the full panel, to its side on the mini-panel's column. Clicking the
 * button itself still does what it always did. A button with nothing to unfold is untouched,
 * apart from having no chevron.
 */
final class PanelFlyout {

    /** Button properties set by the panel's button factories: how to draw the button lit / at rest. */
    static final String HOVER = "qtrace.flyout.hover";
    static final String IDLE  = "qtrace.flyout.idle";
    private static final String SELF = "qtrace.flyout";

    private static final Duration LINGER = Duration.millis(260); // time to travel from the button to the menu

    private final Button button;
    private final String anchor;
    private final boolean sideways;
    private final Popup popup = new Popup();
    private final VBox rows = new VBox(1);
    private final PauseTransition closing = new PauseTransition(LINGER);

    private PanelFlyout(Button button, String anchor, boolean sideways) {
        this.button = button;
        this.anchor = anchor;
        this.sideways = sideways;
        rows.setPadding(new Insets(5));
        rows.setStyle("-fx-background-color: " + QTracePanel.BG_ELEVATED + "; -fx-background-radius: 8;"
            + " -fx-border-color: " + QTracePanel.BORDER + "; -fx-border-radius: 8;"
            + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 12, 0.1, 0, 3);");
        popup.getContent().add(rows);
        popup.setAutoHide(true);
        closing.setOnFinished(e -> popup.hide());
        popup.setOnHidden(e -> { if (!button.isHover()) run(IDLE); });
        rows.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> closing.stop());
        rows.addEventHandler(MouseEvent.MOUSE_EXITED, e -> closing.playFromStart());
        button.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> open());
        button.addEventHandler(MouseEvent.MOUSE_EXITED, e -> { if (popup.isShowing()) closing.playFromStart(); });
        button.addEventHandler(javafx.event.ActionEvent.ACTION, e -> popup.hide());
    }

    /**
     * Makes {@code button} unfold the entries registered for {@code anchor}.
     * @param sideways true on a vertical column of buttons (mini-panel): the menu comes out to
     *                 the side; false on a horizontal toolbar: it comes out underneath
     */
    static void attach(Button button, String anchor, boolean sideways) {
        PanelFlyout flyout = new PanelFlyout(button, anchor, sideways);
        button.getProperties().put(SELF, flyout);
        flyout.run(IDLE); // redraw with the chevron if a module is already there
        // A module loaded after the panel was drawn: the chevron appears then.
        WeakReference<Button> ref = new WeakReference<>(button);
        PanelExtensions.onChange(() -> Platform.runLater(() -> {
            Button b = ref.get();
            if (b != null && !b.isHover() && b.getProperties().get(IDLE) instanceof Runnable idle) idle.run();
        }));
    }

    /** True while the button's menu is out — the button then stays lit although the pointer left it. */
    static boolean isOpenOn(Button button) {
        return button.getProperties().get(SELF) instanceof PanelFlyout f && f.popup.isShowing();
    }

    /**
     * The button's icon, with a small chevron when it has something to unfold. Called by the
     * button factories each time they set the graphic.
     */
    static Node marked(Button button, Node icon, Color color) {
        if (!(button.getProperties().get(SELF) instanceof PanelFlyout f)) return icon;
        if (PanelExtensions.entitled(f.anchor).isEmpty()) return icon;
        Label chevron = new Label(f.sideways ? "›" : "⌄");
        chevron.setFont(Font.font("System", 9));
        chevron.setTextFill(color);
        chevron.setMouseTransparent(true);
        HBox box = new HBox(1, icon, chevron);
        box.setAlignment(f.sideways ? Pos.CENTER : Pos.BOTTOM_CENTER);
        return box;
    }

    private void open() {
        closing.stop();
        if (button.isDisabled() || popup.isShowing()) return;
        List<PanelExtensions.Entry> entries = PanelExtensions.entitled(anchor);
        if (entries.isEmpty() || button.getScene() == null) return;
        rows.getChildren().clear();
        for (PanelExtensions.Entry entry : entries) rows.getChildren().add(row(entry));

        Bounds b = button.localToScreen(button.getBoundsInLocal());
        if (b == null) return;
        if (sideways) popup.show(button, b.getMaxX() + 6, b.getMinY() - 5);
        else          popup.show(button, b.getMinX(), b.getMaxY() + 4);
        // No room on that side of the screen: come out on the other one.
        Rectangle2D screen = Screen.getScreensForRectangle(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight())
            .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        if (sideways && popup.getX() + popup.getWidth() > screen.getMaxX())
            popup.setX(b.getMinX() - 6 - popup.getWidth());
        if (!sideways && popup.getX() + popup.getWidth() > screen.getMaxX())
            popup.setX(b.getMaxX() - popup.getWidth());
        if (!sideways && popup.getY() + popup.getHeight() > screen.getMaxY())
            popup.setY(b.getMinY() - 4 - popup.getHeight());
        run(HOVER);
    }

    private Node row(PanelExtensions.Entry entry) {
        Label l = new Label("›  " + entry.label());
        l.setMaxWidth(Double.MAX_VALUE);
        l.setFont(Font.font("System", 12));
        String idle  = "-fx-text-fill: " + QTracePanel.TEXT_MAIN + "; -fx-padding: 6 14 6 10; -fx-cursor: hand;"
            + " -fx-background-radius: 5; -fx-background-color: transparent;";
        String hover = "-fx-text-fill: " + QTracePanel.TEXT_MAIN + "; -fx-padding: 6 14 6 10; -fx-cursor: hand;"
            + " -fx-background-radius: 5; -fx-background-color: " + QTracePanel.BORDER + ";";
        l.setStyle(idle);
        l.setOnMouseEntered(e -> l.setStyle(hover));
        l.setOnMouseExited(e -> l.setStyle(idle));
        l.setOnMouseClicked(e -> {
            popup.hide();
            try { entry.action().run(); } catch (RuntimeException | LinkageError ex) {
                System.err.println("[qTrace] panel entry '" + entry.label() + "': " + ex);
            }
        });
        return l;
    }

    private void run(String key) {
        if (button.getProperties().get(key) instanceof Runnable r) r.run();
    }
}
