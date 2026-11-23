package com.ditzzy.dsunext.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Layout
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import java.util.Arrays

class EditorGutterView : View {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var editor: CodeEditText? = null

    private var starts: IntArray = intArrayOf(0)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {

    }

    fun attach(editor: CodeEditText) {
        this.editor = editor
        paint.setTypeface(android.graphics.Typeface.MONOSPACE)
        paint.setTextSize(editor.getTextSize())
        paint.setTextAlign(Paint.Align.RIGHT)
        editor.setOnViewportChanged(Runnable(this::invalidate))
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

            }

            override fun afterTextChanged(s: Editable) {
                rebuild(s)
            }
        })
        editor.addOnLayoutChangeListener({ v, l, t, r, b, ol, ot, or, ob ->
            invalidate()
        })
        rebuild(editor.getText()!!)
    }

    private fun rebuild(text: CharSequence) {
        var count: Int = 1
        run {
            var i: Int = 0
            while ((i < text.length)) {
                if ((text[i] == '\n')) {
                    count++
                }
                i++
            }
        }
        starts = IntArray(count)
        var index: Int = 1
        run {
            var i: Int = 0
            while ((i < text.length)) {
                if ((text[i] == '\n')) {
                    starts[index++] = (i + 1)
                }
                i++
            }
        }
        getLayoutParams().width = Math.max(dp(44), (paint.measureText(java.lang.Integer.toString(count)).toInt() + dp(24)))
        requestLayout()
        invalidate()
    }

    fun lineAt(offset: Int): Int {
        val found: Int = Arrays.binarySearch(starts, Math.max(0, offset))
        return (if ((found >= 0)) found else Math.max(0, (-found - 2)))
    }

    fun columnAt(offset: Int): Int {
        return (Math.max(0, offset) - starts[lineAt(offset)])
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (((editor == null) || (editor!!.getLayout() == null))) {
            return
        }
        val layout: Layout = editor!!.getLayout() ?: return
        val top: Int = (editor!!.getScrollY() - editor!!.getTotalPaddingTop())
        val first: Int = layout.getLineForVertical(Math.max(0, top))
        val last: Int = layout.getLineForVertical(Math.max(0, (top + getHeight())))
        val selected: Int = lineAt(editor!!.getSelectionStart())
        run {
            var visual: Int = first
            while ((visual <= last)) {
                val offset: Int = layout.getLineStart(visual)
                val logical: Int = lineAt(offset)
                if ((starts[logical] != offset)) {
                    visual++
                    continue
                }
                paint.setColor(MaterialColors.getColor(this, (if ((logical == selected)) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant)))
                canvas.drawText(java.lang.Integer.toString((logical + 1)), (getWidth() - dp(12)).toFloat(), (layout.getLineBaseline(visual) - top).toFloat(), paint)
                visual++
            }
        }
    }

    private fun dp(value: Int): Int {
        return Math.round((value * getResources().getDisplayMetrics().density))
    }
}
