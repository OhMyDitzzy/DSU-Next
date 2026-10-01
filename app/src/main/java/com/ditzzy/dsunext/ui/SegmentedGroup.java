package com.ditzzy.dsunext.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.ditzzy.dsunext.R;
import com.google.android.material.listitem.ListItemLayout;

public final class SegmentedGroup {

    private SegmentedGroup() {
    }

    public static void apply(LinearLayout group) {
        int visibleCount = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ListItemLayout && child.getVisibility() != View.GONE) {
                visibleCount++;
            }
        }

        int gap = group.getResources().getDimensionPixelSize(R.dimen.segmented_item_gap);
        int position = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof ListItemLayout) || child.getVisibility() == View.GONE) {
                continue;
            }
            ((ListItemLayout) child).updateAppearance(position, visibleCount);

            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            params.topMargin = position == 0 ? 0 : gap;
            child.setLayoutParams(params);
            position++;
        }
    }
}