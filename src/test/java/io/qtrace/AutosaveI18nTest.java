package io.qtrace;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.PropertyResourceBundle;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Autosave / recovery strings: same keys in EN and FR, and formatted ones render cleanly. */
class AutosaveI18nTest {

    private static PropertyResourceBundle bundle(String name) throws Exception {
        try (InputStream in = AutosaveI18nTest.class.getResourceAsStream("/io/qtrace/i18n/" + name)) {
            return new PropertyResourceBundle(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Set<String> keys(PropertyResourceBundle b) {
        Set<String> out = new TreeSet<>();
        for (String k : b.keySet())
            if (k.startsWith("autosave.") || k.startsWith("recover.") || k.startsWith("settings.snapshot.")
                || k.startsWith("settings.autosave") || k.startsWith("dashboard.unstamped") || k.startsWith("graph."))
                out.add(k);
        return out;
    }

    @Test
    void englishAndFrenchHaveTheSameKeys() throws Exception {
        assertEquals(keys(bundle("messages.properties")), keys(bundle("messages_fr.properties")));
    }

    @Test
    void formattedFrenchStringsKeepTheirApostrophes() throws Exception {
        PropertyResourceBundle fr = bundle("messages_fr.properties");
        for (String k : new String[]{"settings.snapshot.c.small", "settings.snapshot.c.largeInterval",
                                     "settings.snapshot.c.largeKeyOnly", "recover.header", "autosave.failed"}) {
            String out = MessageFormat.format(fr.getString(k), 50, 60);
            assertFalse(out.contains("{"), k + " left a placeholder: " + out);
            assertTrue(out.contains("'"), k + " lost its apostrophe: " + out);
        }
        // Plain (non-formatted) strings must not double their quotes.
        assertFalse(fr.getString("settings.snapshot.threshold").contains("''"));
    }
}
