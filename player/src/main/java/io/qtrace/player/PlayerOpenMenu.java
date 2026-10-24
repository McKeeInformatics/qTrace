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

package io.qtrace.player;

import io.qtrace.player.PlayerRecents.Entry;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 📂 Open, the same in the mini-player and in the Player window:
 *
 * <pre>
 *  Trace of « Slide_042.svs »        the open image's .qtrace; greyed out, with why, without one
 *  ─────────
 *  Local file…
 *  qtrace.ca ID or share link…        mini-player only: the window has its own field
 *  Training…                          one entry per module source
 *  ─────────
 *  RECENT
 *  Slide_017.svs       record · 2 h ago
 *  Slide_009.svs       file not found
 *  Clear recent
 * </pre>
 *
 * Built again each time it opens: the open image, the recents and the sources are those of now.
 * {@link #items} decides what is there (no JavaFX, unit-tested); {@link #attach} draws it.
 */
final class PlayerOpenMenu {

    enum Type { OPEN_IMAGE, LOCAL_FILE, ID_OR_LINK, SOURCE, HEADER, RECENT, CLEAR_RECENT, SEPARATOR }

    /**
     * @param source what to open (OPEN_IMAGE, RECENT), else null
     * @param index  the module source's place (SOURCE), else -1
     */
    record Item(Type type, String label, String detail, boolean enabled, String tip, String source, int index) {
        static Item of(Type type, String label) { return new Item(type, label, null, true, null, null, -1); }
    }

    /**
     * @param openImage      the image in the viewer, or null
     * @param openImageTrace its .qtrace on disk, or null
     * @param withIdEntry    the mini-player's "ID or share link…" (the window has a field instead)
     */
    record State(String openImage, File openImageTrace, List<String> sources, List<Entry> recents,
                 Predicate<Entry> available, boolean withIdEntry, long now) {}

    /** What the entries do; called on the FX thread. */
    interface Actions {
        void open(String source);
        void browse();
        void idOrLink();
        void chooseSource(int index);
        void clearRecents();
    }

    private static final String MUTED = "-qt-text-muted";
    private static final String TEXT  = "-qt-text-main";

    private PlayerOpenMenu() {}

    static List<Item> items(State s) {
        List<Item> out = new ArrayList<>();
        if (s.openImage() == null) {
            out.add(new Item(Type.OPEN_IMAGE, "Trace of the open image", "No image open", false, null, null, -1));
        } else {
            String label = "Trace of « " + s.openImage() + " »";
            out.add(s.openImageTrace() == null
                ? new Item(Type.OPEN_IMAGE, label, "No trace yet for this image", false, null, null, -1)
                : new Item(Type.OPEN_IMAGE, label, null, true, s.openImageTrace().getAbsolutePath(),
                           s.openImageTrace().getAbsolutePath(), -1));
        }
        out.add(Item.of(Type.SEPARATOR, null));
        out.add(Item.of(Type.LOCAL_FILE, "Local file…"));
        if (s.withIdEntry()) out.add(Item.of(Type.ID_OR_LINK, "qtrace.ca ID or share link…"));
        for (int i = 0; i < s.sources().size(); i++)
            out.add(new Item(Type.SOURCE, s.sources().get(i), null, true, null, null, i));

        if (!s.recents().isEmpty()) {
            out.add(Item.of(Type.SEPARATOR, null));
            out.add(new Item(Type.HEADER, "RECENT", null, false, null, null, -1));
            for (Entry e : s.recents()) {
                boolean there = s.available().test(e);
                String detail = there ? e.kind().label + " · " + PlayerRecents.ago(e.openedAt(), s.now()) : "file not found";
                out.add(new Item(Type.RECENT, e.title(), detail, there, e.source(), e.source(), -1));
            }
            out.add(Item.of(Type.CLEAR_RECENT, "Clear recent"));
        }
        return out;
    }

    /** Fills {@code button} from {@code state} now and each time it opens. */
    static void attach(MenuButton button, Supplier<State> state, Actions actions) {
        Runnable rebuild = () -> {
            List<Item> items = items(state.get());
            // The recents' details line up in one column, after the longest title.
            double column = 0;
            for (Item item : items)
                if (item.type() == Type.RECENT) column = Math.max(column, textWidth(item.label(), LABEL_FONT));
            List<MenuItem> drawn = new ArrayList<>();
            for (Item item : items) drawn.add(draw(item, actions, column));
            button.getItems().setAll(drawn);
        };
        rebuild.run();
        button.showingProperty().addListener((obs, was, showing) -> { if (showing) rebuild.run(); });
    }

    private static final Font LABEL_FONT = Font.font("System", 12);

    private static double textWidth(String s, Font font) {
        Text text = new Text(s);
        text.setFont(font);
        return Math.ceil(text.getLayoutBounds().getWidth());
    }

    private static MenuItem draw(Item item, Actions actions, double column) {
        if (item.type() == Type.SEPARATOR) return new SeparatorMenuItem();

        Label label = new Label(item.label());
        label.setFont(item.type() == Type.HEADER ? Font.font("System", FontWeight.BOLD, 10) : LABEL_FONT);
        // An inline style, not Theme.text: modena's .menu-item .label rule beats a text fill set in code.
        label.setStyle("-fx-text-fill: " + (item.type() == Type.HEADER ? MUTED : TEXT) + ";");
        HBox row = new HBox(label);
        row.setSpacing(18);
        if (item.detail() != null) {
            if (item.type() == Type.RECENT) label.setMinWidth(column);
            Label detail = new Label(item.detail());
            detail.setFont(Font.font("System", 11));
            detail.setStyle("-fx-text-fill: " + MUTED + ";");
            row.getChildren().add(detail);
        }
        if (item.tip() != null) Tooltip.install(row, new Tooltip(item.tip()));

        CustomMenuItem menuItem = new CustomMenuItem(row);
        menuItem.setId("open-menu-" + item.type().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        if (item.type() == Type.HEADER) {
            menuItem.setHideOnClick(false);
            row.setMouseTransparent(true);
            return menuItem;
        }
        // Greyed by its colour, not by setDisable: a disabled item's tooltip never shows,
        // and the path of a file that moved is what the user needs to see. Only the label
        // fades: the reason beside it stays readable.
        if (!item.enabled()) {
            label.setOpacity(0.45);
            menuItem.setHideOnClick(false);
            return menuItem;
        }
        menuItem.setOnAction(e -> {
            switch (item.type()) {
                case OPEN_IMAGE, RECENT -> actions.open(item.source());
                case LOCAL_FILE -> actions.browse();
                case ID_OR_LINK -> actions.idOrLink();
                case SOURCE -> actions.chooseSource(item.index());
                case CLEAR_RECENT -> actions.clearRecents();
                default -> { }
            }
        });
        return menuItem;
    }
}
