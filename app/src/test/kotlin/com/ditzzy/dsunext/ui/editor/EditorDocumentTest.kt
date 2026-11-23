package com.ditzzy.dsunext.ui.editor

import org.junit.Test
import org.junit.Assert.*

open class EditorDocumentTest {
    @Test
    open fun consecutiveTypingUndoesAsOneEdit() {
        val document: EditorDocument = EditorDocument("")
        document.edit("a", 1, 1, 100L)
        document.edit("ab", 2, 2, 200L)
        document.edit("abc", 3, 3, 300L)
        document.undo()
        assertEquals("", document.text())
        assertFalse(document.dirty())
        document.redo()
        assertEquals("abc", document.text())
        assertEquals(3, document.selectionStart())
    }

    @Test
    open fun pausesCreateSeparateUndoSteps() {
        val document: EditorDocument = EditorDocument("")
        document.edit("a", 1, 1, 100L)
        document.edit("ab", 2, 2, 1000L)
        document.undo()
        assertEquals("a", document.text())
        document.undo()
        assertEquals("", document.text())
    }

    @Test
    open fun aNewEditAfterUndoClearsRedo() {
        val document: EditorDocument = EditorDocument("original")
        document.edit("first", 5, 5, 100L)
        document.undo()
        document.edit("second", 6, 6, 200L)
        assertFalse(document.canRedo())
        document.undo()
        assertEquals("original", document.text())
    }

    @Test
    open fun undoingPastTheSavedSnapshotBecomesDirtyAgain() {
        val document: EditorDocument = EditorDocument("old")
        document.edit("new", 3, 3, 100L)
        document.markSaved("new")
        assertFalse(document.dirty())
        document.undo()
        assertTrue(document.dirty())
        document.redo()
        assertFalse(document.dirty())
    }

    @Test
    open fun selectionIsRestoredAndClamped() {
        val document: EditorDocument = EditorDocument("hello")
        document.selection(1, 4)
        document.edit("ho", 1, 1, 100L)
        document.undo()
        assertEquals(1, document.selectionStart())
        assertEquals(4, document.selectionEnd())
        document.selection(-1, 500)
        assertEquals(0, document.selectionStart())
        assertEquals(5, document.selectionEnd())
    }

    @Test
    open fun manyLargeEditsKeepTheMostRecentUndoWithoutAnUnboundedHistory() {
        val document: EditorDocument = EditorDocument("")
        val large: String = String(CharArray(200000)).replace('\u0000', 'a')
        run {
            var i: Int = 0
            while ((i < 20)) {
                document.edit((large + i), 0, 0, ((i).toLong() * 1000L))
                i++
            }
        }
        var steps: Int = 0
        while (document.canUndo()) {
            document.undo()
            steps++
        }
        assertTrue((steps > 0))
        assertTrue((steps <= 3))
    }
}
