package io.qtrace.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ObjectFingerprintTest {

    private static final ObjectFingerprint.Obj A = new ObjectFingerprint.Obj("Tumor", "Polygon", 10.0, 20.0, 300.0);
    private static final ObjectFingerprint.Obj B = new ObjectFingerprint.Obj("Stroma", "Ellipse", 50.5, 60.25, 42.0);

    @Test
    void isIndependentOfOrder() {
        assertEquals(ObjectFingerprint.of(List.of(A, B)), ObjectFingerprint.of(List.of(B, A)));
    }

    @Test
    void changesWhenAClassChanges() {
        var reclassified = new ObjectFingerprint.Obj("Stroma", "Polygon", 10.0, 20.0, 300.0);
        assertNotEquals(ObjectFingerprint.of(List.of(A, B)), ObjectFingerprint.of(List.of(reclassified, B)));
    }

    @Test
    void changesWhenAnObjectMoves() {
        var moved = new ObjectFingerprint.Obj("Tumor", "Polygon", 11.0, 20.0, 300.0);
        assertNotEquals(ObjectFingerprint.of(List.of(A)), ObjectFingerprint.of(List.of(moved)));
    }

    @Test
    void ignoresFloatingPointNoiseBelowRounding() {
        var noisy = new ObjectFingerprint.Obj("Tumor", "Polygon", 10.0000001, 19.9999999, 300.00001);
        assertEquals(ObjectFingerprint.of(List.of(A)), ObjectFingerprint.of(List.of(noisy)));
    }

    @Test
    void changesWhenAnObjectIsRemoved() {
        assertNotEquals(ObjectFingerprint.of(List.of(A, B)), ObjectFingerprint.of(List.of(A)));
    }

    @Test
    void emptySetHasTheSha256OfTheEmptyString() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                     ObjectFingerprint.of(List.of()));
    }

    @Test
    void canonicalLineIsLocaleIndependent() {
        assertEquals("Stroma|Ellipse|50.5|60.3|42.0", ObjectFingerprint.line(B));
    }
}
