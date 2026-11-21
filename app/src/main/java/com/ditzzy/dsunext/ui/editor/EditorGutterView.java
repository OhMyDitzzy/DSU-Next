package com.ditzzy.dsunext.ui.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;

import java.util.Arrays;

public final class EditorGutterView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private CodeEditText editor;
    private int[] starts = {0};

    public EditorGutterView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void attach(CodeEditText editor) {
        this.editor = editor;
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setTextSize(editor.getTextSize());
        paint.setTextAlign(Paint.Align.RIGHT);
        editor.setOnViewportChanged(this::invalidate);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { rebuild(s); }
        });
        editor.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> invalidate());
        rebuild(editor.getText());
    }

    private void rebuild(CharSequence text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        starts = new int[count];
        int index = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts[index++] = i + 1;
        getLayoutParams().width = Math.max(dp(44), (int) paint.measureText(Integer.toString(count)) + dp(24));
        requestLayout();
        invalidate();
    }

    public int lineAt(int offset) {
        int found = Arrays.binarySearch(starts, Math.max(0, offset));
        return found >= 0 ? found : Math.max(0, -found - 2);
    }
    public int columnAt(int offset) { return Math.max(0, offset) - starts[lineAt(offset)]; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editor == null || editor.getLayout() == null) return;
        Layout layout = editor.getLayout();
        int top = editor.getScrollY() - editor.getTotalPaddingTop();
        int first = layout.getLineForVertical(Math.max(0, top));
        int last = layout.getLineForVertical(Math.max(0, top + getHeight()));
        int selected = lineAt(editor.getSelectionStart());
        for (int visual = first; visual <= last; visual++) {
            int offset = layout.getLineStart(visual);
            int logical = lineAt(offset);
            if (starts[logical] != offset) continue;
            paint.setColor(MaterialColors.getColor(this, logical == selected
                    ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurfaceVariant));
            canvas.drawText(Integer.toString(logical + 1), getWidth() - dp(12), layout.getLineBaseline(visual) - top, paint);
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
