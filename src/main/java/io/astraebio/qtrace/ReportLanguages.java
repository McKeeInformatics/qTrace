package io.astraebio.qtrace;

import java.util.Locale;

/**
 * Languages offered for the generated activity report. A language is identified by
 * a short code (sent to the report endpoint and injected into the LLM prompt) and
 * shown to the user by its endonym.
 */
public final class ReportLanguages {

    private ReportLanguages() {}

    /** {code, endonym} — code is what travels to the portal; endonym is the UI label. */
    public static final String[][] LANGS = {
        { "fr", "Français" },
        { "en", "English" },
        { "es", "Español" },
        { "de", "Deutsch" },
        { "it", "Italiano" },
        { "pt", "Português" },
    };

    /** UI label (endonym) for a code, falling back to the code itself. */
    public static String label(String code) {
        for (String[] l : LANGS) if (l[0].equals(code)) return l[1];
        return code;
    }

    public static boolean isKnown(String code) {
        for (String[] l : LANGS) if (l[0].equals(code)) return true;
        return false;
    }

    /** Default report language: the UI locale's language if supported, else English. */
    public static String defaultCode() {
        String lang = Locale.getDefault().getLanguage();
        return isKnown(lang) ? lang : "en";
    }
}
