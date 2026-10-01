package com.ditzzy.dsunext.ui;

import androidx.annotation.Nullable;

/** One-shot payload for LiveData, so a recreated activity doesn't replay it. */
public final class Event<T> {

    private final T content;
    private boolean handled = false;

    public Event(T content) {
        this.content = content;
    }

    @Nullable
    public synchronized T getContentIfNotHandled() {
        if (handled) {
            return null;
        }
        handled = true;
        return content;
    }
}