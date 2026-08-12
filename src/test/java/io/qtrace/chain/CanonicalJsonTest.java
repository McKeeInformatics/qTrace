package io.qtrace.chain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalJsonTest {

    @Test
    void emptyMap() {
        assertEquals("{}", CanonicalJson.of(Map.of()));
    }

    @Test
    void keysSortedInCodeUnitOrder() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("b", "2");
        m.put("a", "1");
        m.put("Z", "0"); // uppercase sorts before lowercase in code-unit order
        assertEquals("{\"Z\":\"0\",\"a\":\"1\",\"b\":\"2\"}", CanonicalJson.of(m));
    }

    @Test
    void insertionOrderDoesNotMatter() {
        Map<String, String> m1 = new LinkedHashMap<>();
        m1.put("x", "1");
        m1.put("y", "2");
        Map<String, String> m2 = new LinkedHashMap<>();
        m2.put("y", "2");
        m2.put("x", "1");
        assertEquals(CanonicalJson.of(m1), CanonicalJson.of(m2));
    }

    @Test
    void nullValueSerializedAsJsonNull() {
        Map<String, String> m = new HashMap<>();
        m.put("k", null);
        assertEquals("{\"k\":null}", CanonicalJson.of(m));
    }

    @Test
    void escapesQuotesBackslashesAndControlChars() {
        assertEquals("{\"k\":\"a\\\"b\\\\c\\nd\\te\\u0001f\"}",
            CanonicalJson.of(Map.of("k", "a\"b\\c\nd\te\u0001f")));
    }

    @Test
    void unicodeAboveControlRangePassesThrough() {
        assertEquals("{\"k\":\"héma·toxyline\"}",
            CanonicalJson.of(Map.of("k", "héma·toxyline")));
    }
}
