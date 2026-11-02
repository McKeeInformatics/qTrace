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

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** UndoLine reads QuPath's undo stack the way it is laid out in qupath.lib.gui.UndoRedoManager (0.5.1 to 0.7.0). */
class UndoLineTest {

    /** Same fields, same names, same use of the deques as QuPath's UndoRedoManager. */
    @SuppressWarnings("unused")
    static final class FakeManager {
        private final Map<Object, SerializableUndoRedoStack> map = new WeakHashMap<>();

        static final class SerializableUndoRedoStack {
            private byte[] current;
            private final Deque<byte[]> undoStack = new ArrayDeque<>();
            private final Deque<byte[]> redoStack = new ArrayDeque<>();
            SerializableUndoRedoStack(byte[] initial) { current = initial; }
            synchronized void addLatest(byte[] s) { undoStack.push(current); current = s; redoStack.clear(); }
            synchronized void undoOnce() { redoStack.push(current); current = undoStack.pop(); }
            synchronized void redoOnce() { undoStack.push(current); current = redoStack.pop(); }
        }
    }

    final Object viewer = new Object();
    final FakeManager manager = new FakeManager();
    final byte[] s0 = {0}, s1 = {1}, s2 = {2}, s3 = {3};

    @Test
    void theLineIsOldestFirstWithTheCurrentStateAtItsPosition() {
        var stack = new FakeManager.SerializableUndoRedoStack(s0);
        manager.map.put(viewer, stack);
        stack.addLatest(s1);
        stack.addLatest(s2);
        stack.addLatest(s3);
        stack.undoOnce();
        stack.undoOnce();
        UndoLine.Line line = UndoLine.read(manager, viewer);
        assertNotNull(line);
        assertEquals(List.of(s0, s1, s2, s3), line.states());
        assertSame(s1, line.states().get(line.position()));
    }

    @Test
    void noStackForTheViewerIsAnEmptyLine() {
        manager.map.put(viewer, null); // the hierarchy is above maxUndoHierarchySize
        UndoLine.Line line = UndoLine.read(manager, viewer);
        assertNotNull(line);
        assertTrue(line.states().isEmpty());
        assertEquals(-1, line.position());
    }

    @Test
    void aStackWithoutCurrentStateIsAnEmptyLine() {
        manager.map.put(viewer, new FakeManager.SerializableUndoRedoStack(null));
        assertTrue(UndoLine.read(manager, viewer).states().isEmpty());
    }

    @Test
    void anObjectThatIsNotAnUndoManagerCannotBeRead() {
        assertNull(UndoLine.read(new Object(), viewer), "unavailable: the record never says anything was undone");
        assertNull(UndoLine.read(null, viewer));
    }
}
