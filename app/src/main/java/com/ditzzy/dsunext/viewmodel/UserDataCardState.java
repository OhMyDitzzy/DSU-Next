package com.ditzzy.dsunext.viewmodel;

public final class UserDataCardState {

    public boolean selected = false;
    public boolean error = false;
    /** Digits only, the unit is shown by the text field itself. */
    public String text = "";
    public int maximumAllowed = 0;

    public UserDataCardState copy() {
        UserDataCardState copy = new UserDataCardState();
        copy.selected = selected;
        copy.error = error;
        copy.text = text;
        copy.maximumAllowed = maximumAllowed;
        return copy;
    }
}