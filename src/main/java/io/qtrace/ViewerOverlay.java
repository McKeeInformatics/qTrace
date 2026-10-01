package io.qtrace;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import qupath.lib.gui.QuPathGUI;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lays nodes over QuPath's viewers by putting {@code StackPane(viewers, layers…)} where the
 * viewer region was — each layer (e.g. a Pane holding a floating pill) must let clicks through
 * its empty parts ({@code setPickOnBounds(false)}) so the image stays usable.
 *
 * Several layers share the one wrapper (the mini-panel and the replay mini-player can be up at
 * the same time): each {@link #dock} returns that layer's own handle, and the viewers go back
 * to their slot when the last one is {@link #undock() undocked}.
 *
 * QuPath 0.7 wraps the viewer region in the command-finder's HiddenSidesPane (its "content")
 * and moves that pane around when the Analysis pane is shown or hidden, so replacing the
 * content keeps the dock in place; SplitPane items, BorderPane slots and plain Pane children
 * are handled too, for other QuPath versions.
 */
public final class ViewerOverlay {

    /** The wrapper currently standing in for a viewer region, and how to put the region back. */
    private static final class Host {
        final StackPane wrapper = new StackPane();
        Consumer<Node> restore;
    }

    private static final Map<Region, Host> HOSTS = new IdentityHashMap<>(); // FX thread only

    private final Region viewers;
    private final Node layer;
    private boolean docked = true;

    private ViewerOverlay(Region viewers, Node layer) {
        this.viewers = viewers;
        this.layer = layer;
    }

    /** Lays {@code layer} over the viewers; null if QuPath's layout isn't one this knows. FX thread. */
    public static ViewerOverlay dock(QuPathGUI qupath, Node layer) {
        return dockOn(qupath.getViewerManager().getRegion(), layer);
    }

    /** Same, on a given viewer region — the QuPath-free entry point (tests). */
    static ViewerOverlay dockOn(Region viewers, Node layer) {
        Host host = HOSTS.get(viewers);
        if (host == null) {
            Consumer<Node> slot = slotOf(viewers);
            if (slot == null) return null;
            host = new Host();
            host.restore = slot;
            slot.accept(host.wrapper);             // detaches the viewers from their slot…
            host.wrapper.getChildren().add(viewers); // …so they can go under the layers
            HOSTS.put(viewers, host);
        }
        host.wrapper.getChildren().add(layer);
        return new ViewerOverlay(viewers, layer);
    }

    /** Takes this layer off; the viewers return to their slot once no layer is left. */
    public void undock() {
        if (!docked) return;
        docked = false;
        Host host = HOSTS.get(viewers);
        if (host == null) return;
        host.wrapper.getChildren().remove(layer);
        if (host.wrapper.getChildren().size() > 1) return; // another layer is still up
        host.wrapper.getChildren().remove(viewers);
        host.restore.accept(viewers);
        HOSTS.remove(viewers);
    }

    /**
     * How to replace {@code node} in its container: a setter for the slot it occupies, found
     * by walking up its ancestors (a Control's own items/content sit behind skin nodes).
     */
    private static Consumer<Node> slotOf(Node node) {
        Parent p = node.getParent();
        for (int depth = 0; p != null && depth < 6; depth++, p = p.getParent()) {
            if (p instanceof SplitPane sp && sp.getItems().contains(node)) {
                int i = sp.getItems().indexOf(node);
                return n -> {
                    double[] dividers = sp.getDividerPositions();
                    sp.getItems().set(i, n);
                    sp.setDividerPositions(dividers);
                };
            }
            if (p instanceof BorderPane bp) {
                if (bp.getCenter() == node) return bp::setCenter;
                if (bp.getBottom() == node) return bp::setBottom;
                if (bp.getTop() == node)    return bp::setTop;
            }
            if (p instanceof ScrollPane sc && sc.getContent() == node) return sc::setContent;
            Consumer<Node> content = contentSlot(p, node);
            if (content != null) return content;
        }
        if (node.getParent() instanceof Pane pane && !(pane.getParent() instanceof javafx.scene.control.Control)) {
            int i = pane.getChildren().indexOf(node);
            return n -> pane.getChildren().set(i, n);
        }
        return null;
    }

    /** {@code getContent()/setContent(Node)} controls — e.g. ControlsFX's HiddenSidesPane, not on our classpath. */
    private static Consumer<Node> contentSlot(Object container, Node node) {
        try {
            Method get = container.getClass().getMethod("getContent");
            if (get.invoke(container) != node) return null;
            Method set = container.getClass().getMethod("setContent", Node.class);
            return n -> {
                try { set.invoke(container, n); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
