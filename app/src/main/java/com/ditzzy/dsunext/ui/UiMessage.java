package com.ditzzy.dsunext.ui;

import android.content.Context;

import androidx.annotation.StringRes;

/** A string resource plus its arguments, resolved only when the UI is ready to show it. */
public final class UiMessage {

    private final int resId;
    private final Object[] args;

    public UiMessage(@StringRes int resId, Object... args) {
        this.resId = resId;
        this.args = args;
    }

    public String resolve(Context context) {
        return args.length == 0 ? context.getString(resId) : context.getString(resId, args);
    }
}