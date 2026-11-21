package com.ditzzy.dsunext.ui.editor;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatEditText;

public final class CodeEditText extends AppCompatEditText {
    private Runnable viewportChanged;
    private Runnable selectionChanged;

    public CodeEditText(Context context, AttributeSet attrs) { super(context, attrs); }
    public void setOnViewportChanged(Runnable listener) { viewportChanged = listener; }
    public void setOnSelectionChanged(Runnable listener) { selectionChanged = listener; }
    @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        if (viewportChanged != null) viewportChanged.run();
    }
    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (selectionChanged != null) selectionChanged.run();
    }
}
