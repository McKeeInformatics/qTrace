package io.qtrace.net;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.PropertyResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Settings › Network speaks English and French, and every string goes through MessageFormat cleanly. */
class NetworkI18nTest {

    private static PropertyResourceBundle bundle(String name) throws Exception {
        try (InputStream in = NetworkI18nTest.class.getResourceAsStream("/io/qtrace/i18n/" + name)) {
            return new PropertyResourceBundle(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Set<String> networkKeys(PropertyResourceBundle b) {
        Set<String> keys = new TreeSet<>();
        for (String k : Collections.list(b.getKeys()))
            if (k.startsWith("network.") || k.startsWith("settings.network.")) keys.add(k);
        return keys;
    }

    @Test
    void bothLanguagesHaveTheSameKeys() throws Exception {
        Set<String> en = networkKeys(bundle("messages.properties"));
        assertTrue(en.size() > 50, "the Network strings are there");
        assertEquals(en, networkKeys(bundle("messages_fr.properties")));
    }

    @Test
    void everyStringFormatsWithoutAStrayQuote_andWithoutAnEmDash() throws Exception {
        for (String file : new String[]{"messages.properties", "messages_fr.properties"}) {
            PropertyResourceBundle b = bundle(file);
            for (String k : networkKeys(b)) {
                String raw = b.getString(k);
                String out = MessageFormat.format(raw, "A", "B", "C");
                assertFalse(out.contains("{"), file + " " + k + ": unformatted argument");
                // A single quote in a MessageFormat pattern swallows text: they must be doubled.
                assertEquals(raw.replace("''", "").chars().filter(c -> c == '\'').count(), 0, file + " " + k);
                assertFalse(raw.contains("\u2014"), file + " " + k + ": no em dash");
            }
        }
    }
}
