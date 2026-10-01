package com.ditzzy.dsunext.viewmodel;

public final class ImageSizeCardState {

    public boolean selected = false;
    /** Digits only, the unit is shown by the text field itself. */
    public String text = "";

    public ImageSizeCardState copy() {
        ImageSizeCardState copy = new ImageSizeCardState();
        copy.selected = selected;
        copy.text = text;
        return copy;
    }
}