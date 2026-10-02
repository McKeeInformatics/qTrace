package io.qtrace;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class StampSectionsTest {

    private static StampSection section(boolean ready) {
        return new StampSection() {
            @Override public Node node() { return null; }
            @Override public ObservableBooleanValue ready() { return new SimpleBooleanProperty(ready); }
        };
    }

    @AfterEach
    void clear() {
        StampSections.clear();
    }

    @Test
    void noSectionWithoutAModule() {
        assertTrue(StampSections.create().isEmpty());
    }

    @Test
    void aRegisteredSectionIsBuiltAnewForEachDialog() {
        int[] built = {0};
        StampSections.register(() -> { built[0]++; return section(true); });
        assertEquals(1, StampSections.create().size());
        assertEquals(1, StampSections.create().size());
        assertEquals(2, built[0]);
    }

    @Test
    void sectionsComeInRegistrationOrder() {
        StampSection a = section(true), b = section(false);
        StampSections.register(() -> a);
        StampSections.register(() -> b);
        assertEquals(List.of(a, b), StampSections.create());
    }

    @Test
    void aSectionThatFailsToBuildNeverBlocksTheStamp() {
        StampSection ok = section(true);
        Supplier<StampSection> broken = () -> { throw new IllegalStateException("boom"); };
        StampSections.register(broken);
        StampSections.register(() -> null);
        StampSections.register(() -> ok);
        assertEquals(List.of(ok), StampSections.create());
    }

    @Test
    void allReadyOnlyWhenEverySectionIs() {
        assertTrue(StampSections.allReady(List.of()));
        assertTrue(StampSections.allReady(List.of(section(true), section(true))));
        assertFalse(StampSections.allReady(List.of(section(true), section(false))));
    }
}
