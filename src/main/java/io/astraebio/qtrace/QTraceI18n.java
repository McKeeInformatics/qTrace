package io.astraebio.qtrace;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Thin i18n wrapper for QTrace UI strings.
 *
 * Resource bundles are loaded from:
 *   io/astraebio/qtrace/i18n/messages[_<lang>].properties
 *
 * Adding a new language: create messages_<lang>.properties in that package,
 * translate all keys, and ship it alongside the JAR.
 */
public class QTraceI18n {

    private static final String BASE = "io.astraebio.qtrace.i18n.messages";
    private static final ResourceBundle BUNDLE = load();

    private static ResourceBundle load() {
        try {
            return ResourceBundle.getBundle(BASE, Locale.getDefault());
        } catch (MissingResourceException e) {
            try {
                return ResourceBundle.getBundle(BASE, Locale.ENGLISH);
            } catch (MissingResourceException e2) {
                return null;
            }
        }
    }

    /** Returns the localised string for {@code key}, or {@code key} itself if not found. */
    public static String t(String key) {
        if (BUNDLE == null) return key;
        try {
            return BUNDLE.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }
}
