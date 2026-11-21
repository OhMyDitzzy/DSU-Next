package com.ditzzy.dsunext.ui.editor;

import org.junit.Test;

import static org.junit.Assert.*;

public class EditorDocumentTest {
    @Test public void consecutiveTypingUndoesAsOneEdit() {
        EditorDocument document = new EditorDocument("");
        document.edit("a", 1, 1, 100);
        document.edit("ab", 2, 2, 200);
        document.edit("abc", 3, 3, 300);
        document.undo();
        assertEquals("", document.text());
        assertFalse(document.dirty());
        document.redo();
        assertEquals("abc", document.text());
        assertEquals(3, document.selectionStart());
    }

    @Test public void pausesCreateSeparateUndoSteps() {
        EditorDocument document = new EditorDocument("");
        document.edit("a", 1, 1, 100);
        document.edit("ab", 2, 2, 1000);
        document.undo();
        assertEquals("a", document.text());
        document.undo();
        assertEquals("", document.text());
    }

    @Test public void aNewEditAfterUndoClearsRedo() {
        EditorDocument document = new EditorDocument("original");
        document.edit("first", 5, 5, 100);
        document.undo();
        document.edit("second", 6, 6, 200);
        assertFalse(document.canRedo());
        document.undo();
        assertEquals("original", document.text());
    }

    @Test public void undoingPastTheSavedSnapshotBecomesDirtyAgain() {
        EditorDocument document = new EditorDocument("old");
        document.edit("new", 3, 3, 100);
        document.markSaved("new");
        assertFalse(document.dirty());
        document.undo();
        assertTrue(document.dirty());
        document.redo();
        assertFalse(document.dirty());
    }

    @Test public void selectionIsRestoredAndClamped() {
        EditorDocument document = new EditorDocument("hello");
        document.selection(1, 4);
        document.edit("ho", 1, 1, 100);
        document.undo();
        assertEquals(1, document.selectionStart());
        assertEquals(4, document.selectionEnd());
        document.selection(-1, 500);
        assertEquals(0, document.selectionStart());
        assertEquals(5, document.selectionEnd());
    }

    @Test public void manyLargeEditsKeepTheMostRecentUndoWithoutAnUnboundedHistory() {
        EditorDocument document = new EditorDocument("");
        String large = new String(new char[200_000]).replace('\0', 'a');
        for (int i = 0; i < 20; i++) document.edit(large + i, 0, 0, i * 1000L);
        int steps = 0;
        while (document.canUndo()) { document.undo(); steps++; }
        assertTrue(steps > 0);
        assertTrue(steps <= 3);
    }
}
