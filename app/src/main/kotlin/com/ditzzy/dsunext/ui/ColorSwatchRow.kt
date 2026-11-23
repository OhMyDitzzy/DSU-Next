package com.ditzzy.dsunext.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.ditzzy.dsunext.databinding.ItemColorSwatchBinding
import com.ditzzy.dsunext.databinding.RowColorPickerBinding
import com.ditzzy.dsunext.model.ColorPalette
import com.google.android.material.color.MaterialColors
import java.util.EnumMap

class ColorSwatchRow {
    fun interface Listener {
        fun onColorSelected(palette: ColorPalette)
    }

    private val binding: RowColorPickerBinding

    private val swatches: MutableMap<ColorPalette, ItemColorSwatchBinding> = EnumMap<ColorPalette, ItemColorSwatchBinding>(ColorPalette::class.java)

    private constructor(binding: RowColorPickerBinding) {
        this.binding = binding
    }

    fun select(selected: ColorPalette): ColorSwatchRow {
        for (entry in swatches.entries) {
            val isSelected: Boolean = (entry.key == selected)
            entry.value.swatchCheck.setVisibility((if (isSelected) View.VISIBLE else View.GONE))
            entry.value.swatchTouch.setSelected(isSelected)
        }
        return this
    }

    /** Disabled swatches are dimmed and ignore taps, the selection stays visible. */
    fun enabled(enabled: Boolean): ColorSwatchRow {
        binding.swatchContainer.setAlpha((if (enabled) 1f else DISABLED_ALPHA))
        for (swatch in swatches.values) {
            swatch.swatchTouch.setEnabled(enabled)
        }
        return this
    }

    fun visible(visible: Boolean): ColorSwatchRow {
        binding.getRoot().setVisibility((if (visible) View.VISIBLE else View.GONE))
        return this
    }

    fun root(): View {
        return binding.getRoot()
    }

    companion object {
        private const val DISABLED_ALPHA: Float = 0.38f

        /** Inflates the segment and appends it to `group`, call [SegmentedGroup#apply] after. */
        @JvmStatic
        fun create(group: LinearLayout, selected: ColorPalette, listener: Listener): ColorSwatchRow {
            val context: Context = group.getContext()
            val inflater: LayoutInflater = LayoutInflater.from(context)
            val binding: RowColorPickerBinding = RowColorPickerBinding.inflate(inflater, group, false)
            binding.getRoot().setSaveFromParentEnabled(false)
            group.addView(binding.getRoot())
            val row: ColorSwatchRow = ColorSwatchRow(binding)
            for (palette in ColorPalette.DISPLAY_ORDER) {
                val swatch: ItemColorSwatchBinding = ItemColorSwatchBinding.inflate(inflater, binding.swatchContainer, false)
                swatch.swatchCircle.setBackground(circle(context, palette))
                swatch.swatchCheck.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(context, palette.getOnSwatchColor())))
                swatch.swatchTouch.setContentDescription(context.getString(palette.getLabel()))
                swatch.swatchTouch.setOnClickListener({ v ->
                    listener.onColorSelected(palette)
                })
                binding.swatchContainer.addView(swatch.getRoot())
                row.swatches.put(palette, swatch)
            }
            return row.select(selected)
        }

        private fun circle(context: Context, palette: ColorPalette): GradientDrawable {
            val density: Float = context.getResources().getDisplayMetrics().density
            val drawable: GradientDrawable = GradientDrawable()
            drawable.setShape(GradientDrawable.OVAL)
            drawable.setColor(ContextCompat.getColor(context, palette.getSwatchColor()))
            drawable.setStroke(Math.max(1, Math.round(density)), MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0))
            return drawable
        }
    }
}
