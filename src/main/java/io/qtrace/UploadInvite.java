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
package io.qtrace;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What stands at Upload's place in the panel and the mini-panel. Uploading needs an account on
 * qtrace.ca — a certified licence or a basic account: a workstation without one shows Upload
 * greyed, and a click on it opens the window to sign in from (Getting started, which the welcome module registers here).
 */
public final class UploadInvite {

    /** What the panels draw at Upload's place. */
    public enum State {
        /** The upload itself: signed in (an active licence or a basic account) and the upload module. */
        UPLOAD,
        /** Greyed, and a click opens Getting started: no account on this workstation yet. */
        SIGN_IN,
        /** Nothing. */
        NONE
    }

    private static volatile Runnable opener;
    private static final Map<Object, Runnable> LISTENERS = new ConcurrentHashMap<>();

    private UploadInvite() {}

    // ── Rule (pure) ──────────────────────────────────────────────────────────────

    /**
     * @param signedIn       the licence is active, or a basic account is signed in
     * @param pushThere      a module brought the upload and is served
     * @param hasIdentity    a certificate is configured (active or not) or a basic account is signed in
     * @param signInThere    there is a window to sign in from
     */
    static State state(boolean signedIn, boolean pushThere, boolean hasIdentity, boolean signInThere) {
        if (signedIn && pushThere) return State.UPLOAD;
        // A certificate that does not give Upload (inactive, or without the module) is another matter.
        return !hasIdentity && signInThere ? State.SIGN_IN : State.NONE;
    }

    /** What to draw on this workstation, now. */
    public static State state() {
        String licence = QTraceConfig.get().getLicensePath();
        boolean hasLicence = licence != null && !licence.isBlank();
        boolean account = Account.counts(hasLicence, Account.signedIn());
        return state(QTracePluginManager.isEntitled() || account, WorkspacePushes.entitled() != null,
            hasLicence || account, available());
    }

    // ── The window to sign in from ───────────────────────────────────────────────

    /** Called by the welcome module: how to open Getting started. */
    public static void register(Runnable gettingStarted) {
        if (gettingStarted == null) return;
        opener = gettingStarted;
        for (Runnable l : LISTENERS.values()) l.run();
    }

    public static boolean available() {
        return opener != null;
    }

    /** @return false when there is no window to open */
    public static boolean open() {
        Runnable o = opener;
        if (o == null) return false;
        o.run();
        return true;
    }

    /**
     * Called (on the registering thread) when the window becomes available — the welcome
     * module loads after the panel was drawn. One listener per {@code owner}.
     */
    public static void onChange(Object owner, Runnable listener) {
        LISTENERS.put(owner, listener);
    }

    static void clear() {
        opener = null;
        LISTENERS.clear();
    }
}
