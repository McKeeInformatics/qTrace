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
package io.qtrace;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A count of the changes made to the capture: a step captured, a correction, what QuPath's Undo /
 * Redo did. It never goes back, unlike the number of steps in force (a stamp at 5 steps, a Ctrl+Z,
 * a new action: 5 again, yet not the stamped work), so the "already stamped" reminder compares it.
 */
final class CaptureChanges {

    private final AtomicLong count = new AtomicLong();

    void bump() { count.incrementAndGet(); }

    long now() { return count.get(); }

    /** Something changed since {@code mark} (a {@link #now()} taken earlier). */
    boolean since(long mark) { return count.get() != mark; }
}
