package com.ditzzy.dsunext.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.ditzzy.dsunext.databinding.ItemColorSwatchBinding;
import com.ditzzy.dsunext.databinding.RowColorPickerBinding;
import com.ditzzy.dsunext.model.ColorPalette;
import com.google.android.material.color.MaterialColors;

import java.util.EnumMap;
import java.util.Map;

public final class ColorSwatchRow {

    private static final float DISABLED_ALPHA = 0.38f;

    public interface Listener {
        void onColorSelected(ColorPalette palette);
    }

    private final RowColorPickerBinding binding;
    private final Map<ColorPalette, ItemColorSwatchBinding> swatches = new EnumMap<>(ColorPalette.class);

    private ColorSwatchRow(RowColorPickerBinding binding) {
        this.binding = binding;
    }

    /** Inflates the segment and appends it to {@code group}, call {@link SegmentedGroup#apply} after. */
    public static ColorSwatchRow create(LinearLayout group, ColorPalette selected, Listener listener) {
        Context context = group.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        RowColorPickerBinding binding = RowColorPickerBinding.inflate(inflater, group, false);
        binding.getRoot().setSaveFromParentEnabled(false);
        group.addView(binding.getRoot());

        ColorSwatchRow row = new ColorSwatchRow(binding);
        for (ColorPalette palette : ColorPalette.DISPLAY_ORDER) {
            ItemColorSwatchBinding swatch = ItemColorSwatchBinding.inflate(inflater, binding.swatchContainer, false);
            swatch.swatchCircle.setBackground(circle(context, palette));
            swatch.swatchCheck.setImageTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(context, palette.getOnSwatchColor())));
            swatch.swatchTouch.setContentDescription(context.getString(palette.getLabel()));
            swatch.swatchTouch.setOnClickListener(v -> listener.onColorSelected(palette));
            binding.swatchContainer.addView(swatch.getRoot());
            row.swatches.put(palette, swatch);
        }
        return row.select(selected);
    }

    private static GradientDrawable circle(Context context, ColorPalette palette) {
        float density = context.getResources().getDisplayMetrics().density;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(ContextCompat.getColor(context, palette.getSwatchColor()));
        drawable.setStroke(Math.max(1, Math.round(density)),
                MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0));
        return drawable;
    }

    public ColorSwatchRow select(ColorPalette selected) {
        for (Map.Entry<ColorPalette, ItemColorSwatchBinding> entry : swatches.entrySet()) {
            boolean isSelected = entry.getKey() == selected;
            entry.getValue().swatchCheck.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            entry.getValue().swatchTouch.setSelected(isSelected);
        }
        return this;
    }

    /** Disabled swatches are dimmed and ignore taps, the selection stays visible. */
    public ColorSwatchRow enabled(boolean enabled) {
        binding.swatchContainer.setAlpha(enabled ? 1f : DISABLED_ALPHA);
        for (ItemColorSwatchBinding swatch : swatches.values()) {
            swatch.swatchTouch.setEnabled(enabled);
        }
        return this;
    }

    public ColorSwatchRow visible(boolean visible) {
        binding.getRoot().setVisibility(visible ? View.VISIBLE : View.GONE);
        return this;
    }

    public View root() {
        return binding.getRoot();
    }
}
