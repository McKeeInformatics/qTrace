package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A replay restores classifier files into the project: Core must not take that for the user saving them. */
class ObjectClassifierRestoresTest {

    @BeforeEach @AfterEach
    void clean() { ObjectClassifierRestores.clear(); }

    @Test
    void aFileTheReplayAnnouncedIsRecognisedOnce() {
        ObjectClassifierRestores.expect("OC-CK19", "abc", 1_000);
        assertTrue(ObjectClassifierRestores.consume("OC-CK19", "abc", 2_000));
        assertFalse(ObjectClassifierRestores.consume("OC-CK19", "abc", 2_100), "the same announcement covers one write");
    }

    @Test
    void aFileNobodyAnnouncedIsAUserSave() {
        assertFalse(ObjectClassifierRestores.consume("OC-CK19", "abc", 1_000));
    }

    @Test
    void anotherContentUnderTheSameNameIsAUserSave() {
        ObjectClassifierRestores.expect("OC-CK19", "abc", 1_000);
        assertFalse(ObjectClassifierRestores.consume("OC-CK19", "different", 1_500), "the user changed the threshold meanwhile");
    }

    @Test
    void anAnnouncementThatNeverCameTrueExpires() {
        ObjectClassifierRestores.expect("OC-CK19", "abc", 1_000);
        assertFalse(ObjectClassifierRestores.consume("OC-CK19", "abc", 1_000 + 10 * 60_000), "ten minutes later it is a real save");
    }

    @Test
    void severalFilesAreAnnouncedIndependently() {
        ObjectClassifierRestores.expect("A", "1", 1_000);
        ObjectClassifierRestores.expect("B", "2", 1_000);
        assertTrue(ObjectClassifierRestores.consume("B", "2", 1_100));
        assertTrue(ObjectClassifierRestores.consume("A", "1", 1_200));
    }
}
