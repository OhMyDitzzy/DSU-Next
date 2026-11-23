package com.ditzzy.dsunext.ui

import android.content.Context
import androidx.annotation.StringRes

/** A string resource plus its arguments, resolved only when the UI is ready to show it. */
class UiMessage(@StringRes private val resId: Int, private vararg val args: Any?) {
    fun resolve(context: Context): String =
        if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
}
