package com.ditzzy.dsunext.ui.editor

import java.util.ArrayDeque

class EditorDocument {
    private val undo: ArrayDeque<Snapshot> = ArrayDeque<Snapshot>()

    private val redo: ArrayDeque<Snapshot> = ArrayDeque<Snapshot>()

    private var text: String? = null

    private var saved: String? = null

    private var start: Int = 0

    private var end: Int = 0

    private var historyCharacters: Int = 0

    private var lastEdit: Long = java.lang.Long.MIN_VALUE

    private class Snapshot {
        val text: String

        val start: Int

        val end: Int

        constructor(text: String, start: Int, end: Int) {
            this.text = text
            this.start = start
            this.end = end
        }
    }

    constructor(text: String) {
        this.text = text
        saved = text
    }

    fun text(): String {
        return text!!
    }

    fun selectionStart(): Int {
        return start
    }

    fun selectionEnd(): Int {
        return end
    }

    fun dirty(): Boolean {
        return !text!!.equals(saved)
    }

    fun canUndo(): Boolean {
        return !undo.isEmpty()
    }

    fun canRedo(): Boolean {
        return !redo.isEmpty()
    }

    fun selection(start: Int, end: Int) {
        this.start = Math.max(0, Math.min(start, text!!.length))
        this.end = Math.max(0, Math.min(end, text!!.length))
    }

    fun edit(next: String, start: Int, end: Int, now: Long) {
        if (text!!.equals(next)) {
            selection(start, end)
            return
        }
        val bulkEdit: Boolean = (Math.abs((next.length - text!!.length)) > 1)
        if ((((lastEdit == java.lang.Long.MIN_VALUE) || ((now - lastEdit) > 650L)) || bulkEdit)) {
            undo.addLast(snapshot())
            historyCharacters += text!!.length
        }
        for (item in redo) {
            historyCharacters -= item.text.length
        }
        redo.clear()
        text = next
        selection(start, end)
        lastEdit = (if (bulkEdit) java.lang.Long.MIN_VALUE else now)
        trim()
    }

    fun undo() {
        if (undo.isEmpty()) {
            return
        }
        redo.addLast(snapshot())
        historyCharacters += text!!.length
        restore(undo.removeLast())
        trim()
    }

    fun redo() {
        if (redo.isEmpty()) {
            return
        }
        undo.addLast(snapshot())
        historyCharacters += text!!.length
        restore(redo.removeLast())
        trim()
    }

    fun markSaved(snapshot: String) {
        saved = snapshot
        lastEdit = java.lang.Long.MIN_VALUE
    }

    private fun snapshot(): Snapshot {
        return Snapshot(text!!, start, end)
    }

    private fun restore(value: Snapshot) {
        historyCharacters -= value.text.length
        text = value.text
        selection(value.start, value.end)
        lastEdit = java.lang.Long.MIN_VALUE
    }

    private fun trim() {
        while ((((undo.size + redo.size) > (MAX_SNAPSHOTS).toLong()) || (historyCharacters > HISTORY_BUDGET))) {
            if (!undo.isEmpty()) {
                historyCharacters -= undo.removeFirst().text.length
            } else if (!redo.isEmpty()) {
                historyCharacters -= redo.removeFirst().text.length
            } else {
                break
            }
        }
    }

    companion object {
        private const val HISTORY_BUDGET: Int = (512 * 1024)

        private const val MAX_SNAPSHOTS: Int = 40
    }
}
