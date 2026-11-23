package com.ditzzy.dsunext.model

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.ditzzy.dsunext.R

enum class ColorPalette(private val id: Int, private val overlay: Int, private val label: Int, private val swatchColor: Int, private val onSwatchColor: Int) {
    GREEN(0, 0, R.string.color_green, R.color.md_theme_primary, R.color.md_theme_onPrimary),
    RED(1, R.style.ThemeOverlay_DsuNext_Red, R.string.color_red, R.color.md_theme_red_primary, R.color.md_theme_red_onPrimary),
    BLUE(2, R.style.ThemeOverlay_DsuNext_Blue, R.string.color_blue, R.color.md_theme_blue_primary, R.color.md_theme_blue_onPrimary),
    YELLOW(3, R.style.ThemeOverlay_DsuNext_Yellow, R.string.color_yellow, R.color.md_theme_yellow_primary, R.color.md_theme_yellow_onPrimary);

    /** Value persisted in preferences, never change an existing id. */
    fun getId(): Int {
        return id
    }

    /** Theme overlay to apply on top of AppTheme, 0 when the palette is the base theme. */
    @StyleRes
    fun getOverlay(): Int {
        return overlay
    }

    @StringRes
    fun getLabel(): Int {
        return label
    }

    /** Primary color of the palette, resolved for the current light/dark configuration. */
    @ColorRes
    fun getSwatchColor(): Int {
        return swatchColor
    }

    /** Color that stays readable on top of [#getSwatchColor()]. */
    @ColorRes
    fun getOnSwatchColor(): Int {
        return onSwatchColor
    }

    companion object {
        /** Order the swatches are shown in. */
        val DISPLAY_ORDER: Array<ColorPalette> = arrayOf(RED, GREEN, BLUE, YELLOW)

        @JvmStatic
        fun fromId(id: Int): ColorPalette {
            for (palette in values()) {
                if ((palette.id == id)) {
                    return palette
                }
            }
            return GREEN
        }
    }
}
