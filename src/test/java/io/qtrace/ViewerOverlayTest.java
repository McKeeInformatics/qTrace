package io.qtrace;

import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Several layers (mini-panel + mini-player) share one wrapper around the viewers. */
class ViewerOverlayTest {

    @Test
    void twoLayersShareOneWrapperAndTheLastUndockRestoresTheViewers() {
        Region viewers = new Region();
        Pane host = new Pane(viewers);
        Pane first = new Pane(), second = new Pane();

        ViewerOverlay a = ViewerOverlay.dockOn(viewers, first);
        ViewerOverlay b = ViewerOverlay.dockOn(viewers, second);

        assertNotNull(a);
        assertNotNull(b, "a second layer must dock while the first one is there");
        assertEquals(1, host.getChildren().size());
        StackPane wrapper = assertInstanceOf(StackPane.class, host.getChildren().get(0));
        assertEquals(java.util.List.of(viewers, first, second), wrapper.getChildren());

        a.undock();
        assertSame(wrapper, host.getChildren().get(0), "still wrapped while a layer remains");
        assertEquals(java.util.List.of(viewers, second), wrapper.getChildren());

        b.undock();
        assertEquals(java.util.List.of(viewers), host.getChildren());

        a.undock(); // idempotent
        b.undock();
        assertEquals(java.util.List.of(viewers), host.getChildren());
    }

    @Test
    void docksAgainAfterEverythingWasUndocked() {
        Region viewers = new Region();
        Pane host = new Pane(viewers);

        ViewerOverlay.dockOn(viewers, new Pane()).undock();
        Pane layer = new Pane();
        ViewerOverlay again = ViewerOverlay.dockOn(viewers, layer);

        assertNotNull(again);
        StackPane wrapper = assertInstanceOf(StackPane.class, host.getChildren().get(0));
        assertEquals(java.util.List.of(viewers, layer), wrapper.getChildren());
        again.undock();
        assertEquals(java.util.List.of(viewers), host.getChildren());
    }

    @Test
    void unknownLayoutGivesNull() {
        assertNull(ViewerOverlay.dockOn(new Region(), new Pane())); // viewers with no parent
    }
}
