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
 * Who the Validate &amp; Stamp window names as the validator (pure rule, unit-tested). With an
 * active licence it is the certificate's holder; without one, a signed-in basic account names
 * its holder — locked, but <em>self-declared</em>, not certified; otherwise the field is free.
 */
record StampIdentity(String name, Kind kind) {

    enum Kind { CERTIFIED, ACCOUNT, FREE }

    /**
     * @param licenceName the active licence's holder, null without an active licence
     * @param accountName the signed-in account's name, null unless an account stands for this
     *                    workstation ({@link Account#counts})
     * @param configured  the validator name configured in Settings, for the free field
     */
    static StampIdentity resolve(String licenceName, String accountName, String configured) {
        if (licenceName != null) return new StampIdentity(licenceName, Kind.CERTIFIED);
        if (accountName != null && !accountName.isBlank()) return new StampIdentity(accountName, Kind.ACCOUNT);
        return new StampIdentity(configured, Kind.FREE);
    }

    /** The name cannot be edited in the window. */
    boolean locked() { return kind != Kind.FREE; }

    boolean certified() { return kind == Kind.CERTIFIED; }
}
