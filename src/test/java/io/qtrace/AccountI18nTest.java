package io.qtrace;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.PropertyResourceBundle;

import static org.junit.jupiter.api.Assertions.*;

/** The strings of the basic-account states exist in EN and FR and render cleanly. */
class AccountI18nTest {

    private static final String[] KEYS = {"stamp.notcertified.line", "stamp.account.tooltip",
        "panel.account.badge", "settings.account.line"};

    private static PropertyResourceBundle bundle(String name) throws Exception {
        try (InputStream in = AccountI18nTest.class.getResourceAsStream("/io/qtrace/i18n/" + name)) {
            return new PropertyResourceBundle(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void bothLanguagesHaveEveryKey() throws Exception {
        for (String file : new String[]{"messages.properties", "messages_fr.properties"}) {
            PropertyResourceBundle b = bundle(file);
            for (String k : KEYS) assertTrue(b.containsKey(k) && !b.getString(k).isBlank(), file + " lacks " + k);
        }
    }

    @Test
    void theFormattedOnesTakeTheAccountName() throws Exception {
        for (String file : new String[]{"messages.properties", "messages_fr.properties"}) {
            PropertyResourceBundle b = bundle(file);
            for (String k : new String[]{"panel.account.badge", "settings.account.line"}) {
                String out = MessageFormat.format(b.getString(k), "Ada");
                assertTrue(out.contains("Ada") && !out.contains("{"), file + " " + k + ": " + out);
            }
        }
        // read through t(), not formatted: quotes must not be doubled
        assertFalse(bundle("messages_fr.properties").getString("stamp.notcertified.line").contains("''"));
        assertFalse(bundle("messages_fr.properties").getString("stamp.account.tooltip").contains("''"));
    }
}
