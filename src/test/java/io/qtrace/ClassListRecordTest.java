package io.qtrace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What changed in QuPath's class list between two looks at it. */
class ClassListRecordTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private static ClassListRecord.Entry c(String name, Integer rgb) {
        return new ClassListRecord.Entry(name, rgb);
    }

    @Test
    void aNewClassIsAnAddWithItsColour() {
        List<ClassListRecord> d = ClassListRecord.diff(
            List.of(c("Tumor", 0xC80000)), List.of(c("Tumor", 0xC80000), c("Training_Dataset", 0x00FF00)), NOW);
        assertEquals(1, d.size());
        assertEquals(ClassListRecord.ADD, d.get(0).action);
        assertEquals("Training_Dataset", d.get(0).name);
        assertEquals(0x00FF00, d.get(0).colorRgb);
        assertEquals(NOW, d.get(0).timestamp);
    }

    @Test
    void aClassThatIsGoneIsARemove() {
        List<ClassListRecord> d = ClassListRecord.diff(List.of(c("Tumor", 1), c("Stroma", 2)), List.of(c("Tumor", 1)), NOW);
        assertEquals(1, d.size());
        assertEquals(ClassListRecord.REMOVE, d.get(0).action);
        assertEquals("Stroma", d.get(0).name);
    }

    @Test
    void nothingChangedIsNothing() {
        assertTrue(ClassListRecord.diff(List.of(c("Tumor", 1)), List.of(c("Tumor", 1)), NOW).isEmpty());
    }

    @Test
    void aReorderedListIsNotAChange() {
        assertTrue(ClassListRecord.diff(List.of(c("A", 1), c("B", 2)), List.of(c("B", 2), c("A", 1)), NOW).isEmpty());
    }

    @Test
    void aRecolouredClassIsAddedAgainWithTheNewColour() {
        List<ClassListRecord> d = ClassListRecord.diff(List.of(c("Tumor", 1)), List.of(c("Tumor", 2)), NOW);
        assertEquals(1, d.size());
        assertEquals(ClassListRecord.ADD, d.get(0).action);
        assertEquals(2, d.get(0).colorRgb);
    }

    @Test
    void removesComeBeforeAddsAndEachInListOrder() {
        List<ClassListRecord> d = ClassListRecord.diff(
            List.of(c("Old1", 1), c("Keep", 2), c("Old2", 3)), List.of(c("New1", 4), c("Keep", 2), c("New2", 5)), NOW);
        assertEquals(List.of("remove Old1", "remove Old2", "add New1", "add New2"),
            d.stream().map(r -> r.action + " " + r.name).toList());
    }

    @Test
    void aClassWithoutColourIsKept() {
        List<ClassListRecord> d = ClassListRecord.diff(List.of(), List.of(c("Plain", null)), NOW);
        assertNull(d.get(0).colorRgb);
    }
}
