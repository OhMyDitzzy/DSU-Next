package com.ditzzy.dsunext.viewmodel

class UserDataCardState {
    @JvmField
    var selected: Boolean = false

    @JvmField
    var error: Boolean = false

    /** Digits only, the unit is shown by the text field itself. */
    @JvmField
    var text: String = ""

    @JvmField
    var maximumAllowed: Int = 0

    fun copy(): UserDataCardState {
        val copy: UserDataCardState = UserDataCardState()
        copy.selected = selected
        copy.error = error
        copy.text = text
        copy.maximumAllowed = maximumAllowed
        return copy
    }
}
