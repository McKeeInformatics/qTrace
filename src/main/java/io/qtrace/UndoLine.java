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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Reads the undo line QuPath keeps for one viewer - the only place that says what an Undo undid
 * (docs/architecture/undo-redo.md): {@code UndoRedoManager.map} (viewer → its
 * {@code SerializableUndoRedoStack}), whose {@code undoStack}, {@code current} and
 * {@code redoStack} hold serialized hierarchies (byte[]). Private fields, the same from 0.5.1 to
 * 0.7.0; QuPath's public API ({@code canUndo}, {@code canRedo}) says neither what nor how far.
 * Read-only, under the stack's own lock (its methods are synchronized).
 */
final class UndoLine {

    /** Snapshots oldest first (undo stack, current, redo stack), and the index of the current one; empty with -1 when QuPath keeps none. */
    record Line(List<Object> states, int position) {
        static final Line NONE = new Line(List.of(), -1);
    }

    private UndoLine() {}

    /** @return null when {@code manager} cannot be read this way (another QuPath): the record then never says anything was undone */
    static Line read(Object manager, Object viewer) {
        if (manager == null) return null;
        try {
            Map<?, ?> map = (Map<?, ?>) field(manager.getClass(), "map").get(manager);
            Object stack = map.get(viewer);
            if (stack == null) return Line.NONE; // undo is off for this hierarchy (too many objects)
            Class<?> c = stack.getClass();
            Field current = field(c, "current"), undo = field(c, "undoStack"), redo = field(c, "redoStack");
            synchronized (stack) {
                Object now = current.get(stack);
                if (now == null) return Line.NONE;
                List<Object> states = new ArrayList<>(((Deque<?>) undo.get(stack))); // newest first: push() adds at the head
                Collections.reverse(states);
                int position = states.size();
                states.add(now);
                states.addAll((Deque<?>) redo.get(stack));                        // next Redo first
                return new Line(Collections.unmodifiableList(states), position);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
