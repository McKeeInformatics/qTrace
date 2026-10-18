/*
 * qTrace - QuPath workflow provenance extension
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

package io.qtrace.net;

import java.nio.file.Path;
import java.util.Locale;

/**
 * How this workstation reaches the Internet, as chosen in Settings › Network.
 *
 * <ul>
 *   <li>{@link ProxyMode#SYSTEM} (default): the proxy this computer is set to use (JVM options,
 *       {@code HTTPS_PROXY}, Windows / macOS / GNOME settings), see {@link SystemProxy}.</li>
 *   <li>{@link ProxyMode#NONE}: always connect directly.</li>
 *   <li>{@link ProxyMode#MANUAL}: the proxy the IT department gives, optionally with a user name
 *       and password (Basic sign-in).</li>
 * </ul>
 *
 * The certificates trusted for HTTPS are always the Java ones, plus - when chosen - the ones of
 * this computer's store and one institution root certificate (a TLS inspection box). Nothing
 * here can switch certificate verification off.
 *
 * The password is never part of {@link #toString()} and never written to {@code config.json}
 * (see {@link NetworkSettingsStore}).
 */
public record NetworkSettings(
        ProxyMode proxyMode,
        String proxyHost,
        int proxyPort,
        String proxyUser,
        String proxyPassword,
        boolean trustSystemStore,
        Path institutionCa) {

    public enum ProxyMode {
        NONE, SYSTEM, MANUAL;

        /** Lenient: unknown or missing values mean {@link #SYSTEM}, the default. */
        public static ProxyMode parse(String s) {
            if (s == null) return SYSTEM;
            return switch (s.strip().toLowerCase(Locale.ROOT)) {
                case "none", "direct" -> NONE;
                case "manual" -> MANUAL;
                default -> SYSTEM;
            };
        }

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    public NetworkSettings {
        if (proxyMode == null) proxyMode = ProxyMode.SYSTEM;
        proxyHost = blankToNull(proxyHost);
        proxyUser = blankToNull(proxyUser);
        if (proxyPassword != null && proxyPassword.isEmpty()) proxyPassword = null;
    }

    /** What a new installation uses: the computer's proxy, its certificate store, no extra certificate. */
    public static NetworkSettings defaults() {
        return new NetworkSettings(ProxyMode.SYSTEM, null, 0, null, null, true, null);
    }

    /** A manual proxy with a user name: its CONNECT requests carry Basic credentials. */
    public boolean hasProxyCredentials() {
        return proxyMode == ProxyMode.MANUAL && proxyUser != null;
    }

    /** "host:port" of the manual proxy, or null. */
    public String manualProxy() {
        return proxyMode == ProxyMode.MANUAL && proxyHost != null ? proxyHost + ":" + proxyPort : null;
    }

    /** Empty when usable; otherwise why not (an i18n key of {@code QTraceI18n}). */
    public String problem() {
        if (proxyMode != ProxyMode.MANUAL) return "";
        if (proxyHost == null) return "settings.network.err.host";
        if (proxyHost.contains("://") || proxyHost.contains("/") || proxyHost.contains(" "))
            return "settings.network.err.hostform";
        if (proxyPort < 1 || proxyPort > 65535) return "settings.network.err.port";
        return "";
    }

    public NetworkSettings withPassword(String password) {
        return new NetworkSettings(proxyMode, proxyHost, proxyPort, proxyUser, password, trustSystemStore, institutionCa);
    }

    public NetworkSettings withInstitutionCa(Path ca) {
        return new NetworkSettings(proxyMode, proxyHost, proxyPort, proxyUser, proxyPassword, trustSystemStore, ca);
    }

    @Override
    public String toString() {
        return "NetworkSettings[" + proxyMode.id()
            + (manualProxy() != null ? " " + manualProxy() : "")
            + (proxyUser != null ? " user=" + proxyUser + (proxyPassword != null ? " password=(set)" : "") : "")
            + (trustSystemStore ? " +system-store" : "")
            + (institutionCa != null ? " +ca=" + institutionCa : "") + "]";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
