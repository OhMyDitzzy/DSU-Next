package com.ditzzy.dsunext.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Edge-to-edge helpers, the listeners never consume insets so siblings still receive them. */
public final class InsetsUtils {

    private static final int BARS_AND_CUTOUT =
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();

    private InsetsUtils() {
    }

    /** Adds only the side insets (cutouts and navigation bars in landscape). */
    public static void padSides(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(BARS_AND_CUTOUT);
            v.setPadding(left + bars.left, top, right + bars.right, bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** Adds the side and bottom system insets on top of the padding the view already has. */
    public static void padBottomAndSides(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(BARS_AND_CUTOUT);
            v.setPadding(left + bars.left, top, right + bars.right, bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** Adds the side and bottom system insets on top of the margins the view already has. */
    public static void marginBottomAndSides(View view) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        final int left = params.leftMargin;
        final int right = params.rightMargin;
        final int bottom = params.bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(BARS_AND_CUTOUT);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.leftMargin = left + bars.left;
            lp.rightMargin = right + bars.right;
            lp.bottomMargin = bottom + bars.bottom;
            v.setLayoutParams(lp);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}