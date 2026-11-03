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

import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInAttemptsTest {

    @Test
    void aNewSignInStopsTheOneBefore() {
        // City of Hope, 2026-11-04: three codes polled at once by one Getting started, one tab each
        SignInAttempts attempts = new SignInAttempts();
        BooleanSupplier first = attempts.next();
        BooleanSupplier second = attempts.next();
        assertTrue(first.getAsBoolean());
        assertFalse(second.getAsBoolean());
    }

    @Test
    void closingTheWindowStopsTheCurrentOne() {
        SignInAttempts attempts = new SignInAttempts();
        BooleanSupplier current = attempts.next();
        attempts.cancel();
        assertTrue(current.getAsBoolean());
    }

    @Test
    void aSignInAfterCancelRunsAgain() {
        SignInAttempts attempts = new SignInAttempts();
        attempts.next();
        attempts.cancel();
        assertFalse(attempts.next().getAsBoolean());
    }

    @Test
    void cancelWithNothingRunningIsHarmless() {
        new SignInAttempts().cancel();
    }
}
