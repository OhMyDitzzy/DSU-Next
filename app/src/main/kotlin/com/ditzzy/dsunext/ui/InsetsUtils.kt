package com.ditzzy.dsunext.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Edge-to-edge helpers, the listeners never consume insets so siblings still receive them. */
class InsetsUtils {
    private constructor() {

    }

    companion object {
        private val BARS_AND_CUTOUT: Int = (WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())

        /** Adds only the side insets (cutouts and navigation bars in landscape). */
        @JvmStatic
        fun padSides(view: View) {
            val left: Int = view.getPaddingLeft()
            val top: Int = view.getPaddingTop()
            val right: Int = view.getPaddingRight()
            val bottom: Int = view.getPaddingBottom()
            ViewCompat.setOnApplyWindowInsetsListener(view, callback1@ { v, insets ->
                val bars: Insets = insets.getInsets(BARS_AND_CUTOUT)
                v.setPadding((left + bars.left), top, (right + bars.right), bottom)
                return@callback1 insets
            })
            ViewCompat.requestApplyInsets(view)
        }

        /** Adds the side and bottom system insets on top of the padding the view already has. */
        @JvmStatic
        fun padBottomAndSides(view: View) {
            val left: Int = view.getPaddingLeft()
            val top: Int = view.getPaddingTop()
            val right: Int = view.getPaddingRight()
            val bottom: Int = view.getPaddingBottom()
            ViewCompat.setOnApplyWindowInsetsListener(view, callback2@ { v, insets ->
                val bars: Insets = insets.getInsets(BARS_AND_CUTOUT)
                v.setPadding((left + bars.left), top, (right + bars.right), (bottom + bars.bottom))
                return@callback2 insets
            })
            ViewCompat.requestApplyInsets(view)
        }

        /** Adds the side and bottom system insets on top of the margins the view already has. */
        @JvmStatic
        fun marginBottomAndSides(view: View) {
            val params: ViewGroup.MarginLayoutParams = (view.getLayoutParams() as ViewGroup.MarginLayoutParams)
            val left: Int = params.leftMargin
            val right: Int = params.rightMargin
            val bottom: Int = params.bottomMargin
            ViewCompat.setOnApplyWindowInsetsListener(view, callback3@ { v, insets ->
                val bars: Insets = insets.getInsets(BARS_AND_CUTOUT)
                val lp: ViewGroup.MarginLayoutParams = (v.getLayoutParams() as ViewGroup.MarginLayoutParams)
                lp.leftMargin = (left + bars.left)
                lp.rightMargin = (right + bars.right)
                lp.bottomMargin = (bottom + bars.bottom)
                v.setLayoutParams(lp)
                return@callback3 insets
            })
            ViewCompat.requestApplyInsets(view)
        }
    }
}
