package io.qtrace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A plugin locking an annotation is not an edit of it: the two fragments compare equal once the lock is left out. */
class AnnotationLockComparisonTest {

    private static String fragment(String json) {
        return "// Replay manual annotation: Ellipse\n"
            + "def _b_x = java.util.Base64.getDecoder().decode(\""
            + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)) + "\")\n"
            + "if (_obj_x != null) addObject(_obj_x)";
    }

    @Test
    void aLockedAndAnUnlockedAnnotationCompareEqual() {
        String open   = fragment("{\"id\":\"a\",\"properties\":{\"objectType\":\"annotation\",\"metadata\":{}}}");
        String locked = fragment("{\"id\":\"a\",\"properties\":{\"objectType\":\"annotation\",\"isLocked\":true,\"metadata\":{}}}");
        assertNotEquals(open, locked);
        assertEquals(AnnotationFragments.withoutLock(open), AnnotationFragments.withoutLock(locked));
    }

    @Test
    void aMovedAnnotationStillDiffers() {
        String a = fragment("{\"id\":\"a\",\"geometry\":{\"x\":1},\"properties\":{\"isLocked\":true}}");
        String b = fragment("{\"id\":\"a\",\"geometry\":{\"x\":2},\"properties\":{\"isLocked\":true}}");
        assertNotEquals(AnnotationFragments.withoutLock(a), AnnotationFragments.withoutLock(b));
    }

    @Test
    void whatIsNotAFragmentIsLeftAlone() {
        assertNull(AnnotationFragments.withoutLock(null));
        assertEquals("no payload", AnnotationFragments.withoutLock("no payload"));
    }
}
