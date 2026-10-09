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

/**
 * The provisioning module ({@link Provisioner}), as it registered itself at startup. The
 * welcome module asks it what to install and to sign in; without it there is nothing to set up.
 */
public final class Provisioners {

    private static volatile Provisioner current;

    private Provisioners() {}

    public static void register(Provisioner provisioner) {
        if (provisioner != null) current = provisioner;
    }

    /** Null when the provisioning module is not on this workstation. */
    public static Provisioner current() {
        return current;
    }

    /** False without the provisioning module. */
    public static boolean gettingStartedPending() {
        Provisioner p = current;
        return p != null && p.gettingStartedPending();
    }

    static void clear() {
        current = null;
    }
}
