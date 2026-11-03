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
package io.qtrace.welcome;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The sign-ins of one Getting started window: one at a time. A new "Sign in" (or "Try again")
 * stops the one before, which stops polling qtrace.ca; closing the window stops the current one.
 *
 * The window used to share one cancel flag and set it back to false at each sign-in: every
 * earlier code kept polling and each click opened one more browser tab with one more code
 * (City of Hope, 2026-11-04: three codes polled at once).
 */
final class SignInAttempts {

    private AtomicBoolean current;

    /** Starts a sign-in, stopping the one before; what it returns is that sign-in's "cancelled?". */
    synchronized BooleanSupplier next() {
        if (current != null) current.set(true);
        AtomicBoolean mine = new AtomicBoolean();
        current = mine;
        return mine::get;
    }

    /** Stops the current sign-in, if any (Cancel, the window closed). */
    synchronized void cancel() {
        if (current != null) current.set(true);
    }
}
