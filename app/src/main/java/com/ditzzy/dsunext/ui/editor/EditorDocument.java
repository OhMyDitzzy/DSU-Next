package com.ditzzy.dsunext.ui.editor;

import java.util.ArrayDeque;

/* Snapshots are grouped while typing and capped by text size so a large file cannot fill the undo stack. */
public final class EditorDocument {
    private static final int HISTORY_BUDGET = 512 * 1024;
    private static final int MAX_SNAPSHOTS = 40;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
    private String text;
    private String saved;
    private int start;
    private int end;
    private int historyCharacters;
    private long lastEdit = Long.MIN_VALUE;

    private static final class Snapshot {
        final String text;
        final int start;
        final int end;
        Snapshot(String text, int start, int end) { this.text = text; this.start = start; this.end = end; }
    }

    public EditorDocument(String text) { this.text = text; saved = text; }
    public String text() { return text; }
    public int selectionStart() { return start; }
    public int selectionEnd() { return end; }
    public boolean dirty() { return !text.equals(saved); }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void selection(int start, int end) {
        this.start = Math.max(0, Math.min(start, text.length()));
        this.end = Math.max(0, Math.min(end, text.length()));
    }

    public void edit(String next, int start, int end, long now) {
        if (text.equals(next)) { selection(start, end); return; }
        boolean bulkEdit = Math.abs(next.length() - text.length()) > 1;
        if (lastEdit == Long.MIN_VALUE || now - lastEdit > 650 || bulkEdit) {
            undo.addLast(snapshot());
            historyCharacters += text.length();
        }
        for (Snapshot item : redo) historyCharacters -= item.text.length();
        redo.clear();
        text = next;
        selection(start, end);
        lastEdit = bulkEdit ? Long.MIN_VALUE : now;
        trim();
    }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.addLast(snapshot());
        historyCharacters += text.length();
        restore(undo.removeLast());
        trim();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.addLast(snapshot());
        historyCharacters += text.length();
        restore(redo.removeLast());
        trim();
    }

    public void markSaved(String snapshot) { saved = snapshot; lastEdit = Long.MIN_VALUE; }
    private Snapshot snapshot() { return new Snapshot(text, start, end); }
    private void restore(Snapshot value) {
        historyCharacters -= value.text.length();
        text = value.text;
        selection(value.start, value.end);
        lastEdit = Long.MIN_VALUE;
    }
    private void trim() {
        while (undo.size() + redo.size() > MAX_SNAPSHOTS || historyCharacters > HISTORY_BUDGET) {
            if (!undo.isEmpty()) historyCharacters -= undo.removeFirst().text.length();
            else if (!redo.isEmpty()) historyCharacters -= redo.removeFirst().text.length();
            else break;
        }
    }
}
