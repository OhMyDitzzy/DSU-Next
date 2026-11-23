package com.ditzzy.dsunext.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.ditzzy.dsunext.R
import com.google.android.material.listitem.ListItemLayout

class SegmentedGroup {
    private constructor() {

    }

    companion object {
        @JvmStatic
        fun apply(group: LinearLayout) {
            var visibleCount: Int = 0
            run {
                var i: Int = 0
                while ((i < group.getChildCount())) {
                    val child: View = group.getChildAt(i)!!
                    if (((child is ListItemLayout) && (child.getVisibility() != View.GONE))) {
                        visibleCount++
                    }
                    i++
                }
            }
            val gap: Int = group.getResources().getDimensionPixelSize(R.dimen.segmented_item_gap)
            var position: Int = 0
            run {
                var i: Int = 0
                while ((i < group.getChildCount())) {
                    val child: View = group.getChildAt(i)!!
                    if ((!(child is ListItemLayout) || (child.getVisibility() == View.GONE))) {
                        i++
                        continue
                    }
                    (child as ListItemLayout).updateAppearance(position, visibleCount)
                    val params: ViewGroup.MarginLayoutParams = (child.getLayoutParams() as ViewGroup.MarginLayoutParams)
                    params.topMargin = (if ((position == 0)) 0 else gap)
                    child.setLayoutParams(params)
                    position++
                    i++
                }
            }
        }
    }
}
