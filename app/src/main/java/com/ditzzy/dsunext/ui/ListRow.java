package com.ditzzy.dsunext.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import java.util.function.Consumer;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.ditzzy.dsunext.databinding.RowListItemBinding;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Thin wrapper over the segmented list item layout, so screens read as a list of rows. */
public final class ListRow {

    private final RowListItemBinding binding;

    private ListRow(RowListItemBinding binding) {
        this.binding = binding;
    }

    /** Inflates a row and appends it to {@code group}, call {@link SegmentedGroup#apply} after. */
    public static ListRow create(LinearLayout group) {
        RowListItemBinding binding =
                RowListItemBinding.inflate(LayoutInflater.from(group.getContext()), group, false);
        group.addView(binding.getRoot());
        return new ListRow(binding);
    }

    public ListRow icon(@DrawableRes int icon) {
        binding.itemIcon.setImageResource(icon);
        return this;
    }

    public ListRow title(@StringRes int title) {
        binding.itemTitle.setText(title);
        return this;
    }

    public ListRow title(CharSequence title) {
        binding.itemTitle.setText(title);
        return this;
    }

    public ListRow supporting(@StringRes int text) {
        return supporting(binding.getRoot().getContext().getString(text));
    }

    public ListRow supporting(@Nullable CharSequence text) {
        binding.itemSupporting.setText(text);
        binding.itemSupporting.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        return this;
    }

    public ListRow trailingText(@Nullable CharSequence text) {
        binding.itemTrailingText.setText(text);
        binding.itemTrailingText.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        return this;
    }

    public ListRow trailingIcon(@DrawableRes int icon) {
        binding.itemTrailingIcon.setImageResource(icon);
        binding.itemTrailingIcon.setVisibility(View.VISIBLE);
        return this;
    }

    public ListRow hideTrailingIcon() {
        binding.itemTrailingIcon.setVisibility(View.GONE);
        return this;
    }

    /**
     * Turns the row into a toggle. The switch is purely visual (it is not clickable by itself),
     * so the row click flips it and reports the new value.
     */
    public ListRow toggle(boolean checked, Consumer<Boolean> onToggled) {
        binding.itemSwitch.setVisibility(View.VISIBLE);
        binding.itemSwitch.setChecked(checked);
        binding.itemCard.setOnClickListener(v -> {
            boolean next = !binding.itemSwitch.isChecked();
            binding.itemSwitch.setChecked(next);
            onToggled.accept(next);
        });
        return this;
    }

    public ListRow checked(boolean checked) {
        binding.itemSwitch.setChecked(checked);
        return this;
    }

    public ListRow onClick(View.OnClickListener listener) {
        binding.itemCard.setOnClickListener(listener);
        return this;
    }

    /** For rows that only present information. */
    public ListRow notClickable() {
        binding.itemCard.setClickable(false);
        binding.itemCard.setFocusable(false);
        return this;
    }

    public ListRow enabled(boolean enabled) {
        binding.itemCard.setEnabled(enabled);
        binding.itemSwitch.setEnabled(enabled);
        return this;
    }

    public ListRow visible(boolean visible) {
        binding.getRoot().setVisibility(visible ? View.VISIBLE : View.GONE);
        return this;
    }

    public MaterialSwitch switchView() {
        return binding.itemSwitch;
    }

    public com.google.android.material.button.MaterialButton trailingButton() {
        return binding.itemTrailingButton;
    }

    /** Shows an icon button at the end of the row, it handles its own clicks. */
    public ListRow trailingButton(@DrawableRes int icon, CharSequence description, View.OnClickListener listener) {
        binding.itemTrailingButton.setIcon(androidx.appcompat.content.res.AppCompatResources
                .getDrawable(binding.getRoot().getContext(), icon));
        binding.itemTrailingButton.setContentDescription(description);
        binding.itemTrailingButton.setOnClickListener(listener);
        binding.itemTrailingButton.setVisibility(View.VISIBLE);
        return this;
    }

    public ListRow hideTrailingButton() {
        binding.itemTrailingButton.setVisibility(View.GONE);
        return this;
    }

    public View root() {
        return binding.getRoot();
    }
}