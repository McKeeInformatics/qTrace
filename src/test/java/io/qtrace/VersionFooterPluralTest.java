package io.qtrace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

/** The Version window's footer counts in words that agree with the number: "1 step · 1 session", not "1 sessions". */
class VersionFooterPluralTest {

    private static String footer(Locale locale, String key, Object... args) {
        ResourceBundle b = ResourceBundle.getBundle("io.qtrace.i18n.messages", locale);
        return new MessageFormat(b.getString(key), locale).format(args);
    }

    @Test
    void oneStepOneSessionInEnglish() {
        assertEquals("1 step · 1 session", footer(Locale.ENGLISH, "graph.timeline.footer", 1, 1));
        assertEquals("3 steps · 2 sessions", footer(Locale.ENGLISH, "graph.timeline.footer", 3, 2));
        assertEquals("1 step · session #2 only — click beside the nodes to list them all",
            footer(Locale.ENGLISH, "graph.timeline.footer.session", 1, 2));
    }

    @Test
    void oneStepOneSessionInFrench() {
        assertEquals("1 étape · 1 session", footer(Locale.FRENCH, "graph.timeline.footer", 1, 1));
        assertEquals("3 étapes · 2 sessions", footer(Locale.FRENCH, "graph.timeline.footer", 3, 2));
        assertEquals("1 étape · session #2 seulement — cliquez à côté des nœuds pour tout lister",
            footer(Locale.FRENCH, "graph.timeline.footer.session", 1, 2));
    }
}
