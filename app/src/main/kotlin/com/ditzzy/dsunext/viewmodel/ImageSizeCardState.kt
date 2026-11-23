package com.ditzzy.dsunext.viewmodel

class ImageSizeCardState {
    @JvmField
    var selected: Boolean = false

    /** Digits only, the unit is shown by the text field itself. */
    @JvmField
    var text: String = ""

    fun copy(): ImageSizeCardState {
        val copy: ImageSizeCardState = ImageSizeCardState()
        copy.selected = selected
        copy.text = text
        return copy
    }
}
