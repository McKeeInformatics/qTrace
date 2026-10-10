package io.qtrace;

import org.junit.jupiter.api.Test;

import static io.qtrace.StampIdentity.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Who the stamp dialog names as the validator: the certified holder, a signed-in account, or whatever is typed. */
class StampIdentityTest {

    @Test
    void anActiveLicenceNamesItsHolder() {
        StampIdentity i = StampIdentity.resolve("Dr. Certified", "Ada", "typed");
        assertEquals(CERTIFIED, i.kind());
        assertEquals("Dr. Certified", i.name());
        assertTrue(i.locked());
    }

    @Test
    void withoutALicenceASignedInAccountNamesItsHolderAndIsLocked() {
        StampIdentity i = StampIdentity.resolve(null, "Ada Lovelace", "typed");
        assertEquals(ACCOUNT, i.kind());
        assertEquals("Ada Lovelace", i.name());
        assertTrue(i.locked());
    }

    @Test
    void anAccountWithoutAnameFallsBackToTheFreeField() {
        for (String blank : new String[]{null, "", "  "}) {
            StampIdentity i = StampIdentity.resolve(null, blank, "typed");
            assertEquals(FREE, i.kind());
            assertEquals("typed", i.name());
            assertFalse(i.locked());
        }
    }

    @Test
    void noAccountNoLicenceIsTodaysFreeField() {
        StampIdentity i = StampIdentity.resolve(null, null, "Dr. Typed");
        assertEquals(FREE, i.kind());
        assertEquals("Dr. Typed", i.name());
        assertFalse(i.locked());
    }

    @Test
    void onlyACertifiedIdentityIsCertified() {
        assertTrue(StampIdentity.resolve("x", null, "").certified());
        assertFalse(StampIdentity.resolve(null, "x", "").certified());
        assertFalse(StampIdentity.resolve(null, null, "x").certified());
    }
}
