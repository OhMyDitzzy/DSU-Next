package com.ditzzy.dsunext.model;

import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;
import androidx.annotation.StyleRes;

import com.ditzzy.dsunext.R;

public enum ColorPalette {
    GREEN(0, 0, R.string.color_green, R.color.md_theme_primary, R.color.md_theme_onPrimary),
    RED(1, R.style.ThemeOverlay_DsuNext_Red, R.string.color_red,
            R.color.md_theme_red_primary, R.color.md_theme_red_onPrimary),
    BLUE(2, R.style.ThemeOverlay_DsuNext_Blue, R.string.color_blue,
            R.color.md_theme_blue_primary, R.color.md_theme_blue_onPrimary),
    YELLOW(3, R.style.ThemeOverlay_DsuNext_Yellow, R.string.color_yellow,
            R.color.md_theme_yellow_primary, R.color.md_theme_yellow_onPrimary);

    /** Order the swatches are shown in. */
    public static final ColorPalette[] DISPLAY_ORDER = {RED, GREEN, BLUE, YELLOW};

    private final int id;
    private final int overlay;
    private final int label;
    private final int swatchColor;
    private final int onSwatchColor;

    ColorPalette(int id, @StyleRes int overlay, @StringRes int label,
                 @ColorRes int swatchColor, @ColorRes int onSwatchColor) {
        this.id = id;
        this.overlay = overlay;
        this.label = label;
        this.swatchColor = swatchColor;
        this.onSwatchColor = onSwatchColor;
    }

    /** Value persisted in preferences, never change an existing id. */
    public int getId() {
        return id;
    }

    /** Theme overlay to apply on top of AppTheme, 0 when the palette is the base theme. */
    @StyleRes
    public int getOverlay() {
        return overlay;
    }

    @StringRes
    public int getLabel() {
        return label;
    }

    /** Primary color of the palette, resolved for the current light/dark configuration. */
    @ColorRes
    public int getSwatchColor() {
        return swatchColor;
    }

    /** Color that stays readable on top of {@link #getSwatchColor()}. */
    @ColorRes
    public int getOnSwatchColor() {
        return onSwatchColor;
    }

    public static ColorPalette fromId(int id) {
        for (ColorPalette palette : values()) {
            if (palette.id == id) {
                return palette;
            }
        }
        return GREEN;
    }
}
