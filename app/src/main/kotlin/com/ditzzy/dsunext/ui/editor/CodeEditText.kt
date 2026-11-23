package com.ditzzy.dsunext.ui.editor

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

class CodeEditText : AppCompatEditText {
    private var viewportChanged: Runnable? = null

    private var selectionChanged: Runnable? = null

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {

    }

    fun setOnViewportChanged(listener: Runnable) {
        viewportChanged = listener
    }

    fun setOnSelectionChanged(listener: Runnable) {
        selectionChanged = listener
    }

    protected override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if ((viewportChanged != null)) {
            viewportChanged!!.run()
        }
    }

    protected override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        if ((selectionChanged != null)) {
            selectionChanged!!.run()
        }
    }
}
