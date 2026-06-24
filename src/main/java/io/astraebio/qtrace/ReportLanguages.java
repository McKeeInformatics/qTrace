/*
 * qTrace — QuPath workflow provenance extension
 * Copyright (C) 2026 Romain Tourte
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

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
