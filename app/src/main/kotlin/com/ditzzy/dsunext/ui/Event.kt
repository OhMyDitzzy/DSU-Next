package com.ditzzy.dsunext.ui

/** One-shot payload for LiveData, so a recreated activity doesn't replay it. */
class Event<T> {
    private val content: T

    private var handled: Boolean = false

    constructor(content: T) {
        this.content = content
    }

    @Synchronized
    fun getContentIfNotHandled(): T? {
        if (handled) {
            return null
        }
        handled = true
        return content
    }
}
