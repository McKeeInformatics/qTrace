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
