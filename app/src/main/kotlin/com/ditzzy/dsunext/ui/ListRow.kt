package com.ditzzy.dsunext.ui

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import java.util.function.Consumer
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.ditzzy.dsunext.databinding.RowListItemBinding
import com.google.android.material.materialswitch.MaterialSwitch

/** Thin wrapper over the segmented list item layout, so screens read as a list of rows. */
class ListRow {
    private val binding: RowListItemBinding

    private constructor(binding: RowListItemBinding) {
        this.binding = binding
    }

    fun icon(@DrawableRes icon: Int): ListRow {
        binding.itemIcon.setImageResource(icon)
        return this
    }

    fun title(@StringRes title: Int): ListRow {
        binding.itemTitle.setText(title)
        return this
    }

    fun title(title: CharSequence): ListRow {
        binding.itemTitle.setText(title)
        return this
    }

    fun supporting(@StringRes text: Int): ListRow {
        return supporting(binding.getRoot().getContext().getString(text))
    }

    fun supporting(text: CharSequence?): ListRow {
        binding.itemSupporting.setText(text)
        binding.itemSupporting.setVisibility((if (TextUtils.isEmpty(text)) View.GONE else View.VISIBLE))
        return this
    }

    fun trailingText(text: CharSequence?): ListRow {
        binding.itemTrailingText.setText(text)
        binding.itemTrailingText.setVisibility((if (TextUtils.isEmpty(text)) View.GONE else View.VISIBLE))
        return this
    }

    fun trailingIcon(@DrawableRes icon: Int): ListRow {
        binding.itemTrailingIcon.setImageResource(icon)
        binding.itemTrailingIcon.setVisibility(View.VISIBLE)
        return this
    }

    fun trailingIconView(): android.widget.ImageView {
        return binding.itemTrailingIcon
    }

    fun hideTrailingIcon(): ListRow {
        binding.itemTrailingIcon.setVisibility(View.GONE)
        return this
    }

    /**
     * Turns the row into a toggle. The switch is purely visual (it is not clickable by itself),
     * so the row click flips it and reports the new value.
     */
    fun toggle(checked: Boolean, onToggled: Consumer<Boolean>): ListRow {
        binding.itemSwitch.setVisibility(View.VISIBLE)
        binding.itemSwitch.setChecked(checked)
        binding.itemCard.setOnClickListener({ v ->
            val next: Boolean = !binding.itemSwitch.isChecked()
            binding.itemSwitch.setChecked(next)
            onToggled.accept(next)
        })
        return this
    }

    fun checked(checked: Boolean): ListRow {
        binding.itemSwitch.setChecked(checked)
        return this
    }

    fun onClick(listener: View.OnClickListener): ListRow {
        binding.itemCard.setOnClickListener(listener)
        return this
    }

    /** For rows that only present information. */
    fun notClickable(): ListRow {
        binding.itemCard.setClickable(false)
        binding.itemCard.setFocusable(false)
        return this
    }

    fun enabled(enabled: Boolean): ListRow {
        binding.itemCard.setEnabled(enabled)
        binding.itemSwitch.setEnabled(enabled)
        return this
    }

    fun visible(visible: Boolean): ListRow {
        binding.getRoot().setVisibility((if (visible) View.VISIBLE else View.GONE))
        return this
    }

    fun switchView(): MaterialSwitch {
        return binding.itemSwitch
    }

    fun trailingButton(): com.google.android.material.button.MaterialButton {
        return binding.itemTrailingButton
    }

    /** Shows an icon button at the end of the row, it handles its own clicks. */
    fun trailingButton(@DrawableRes icon: Int, description: CharSequence, listener: View.OnClickListener): ListRow {
        binding.itemTrailingButton.setIcon(androidx.appcompat.content.res.AppCompatResources.getDrawable(binding.getRoot().getContext(), icon))
        binding.itemTrailingButton.setContentDescription(description)
        binding.itemTrailingButton.setOnClickListener(listener)
        binding.itemTrailingButton.setVisibility(View.VISIBLE)
        return this
    }

    fun hideTrailingButton(): ListRow {
        binding.itemTrailingButton.setVisibility(View.GONE)
        return this
    }

    fun root(): View {
        return binding.getRoot()
    }

    companion object {
        @JvmStatic
        fun create(group: LinearLayout): ListRow {
            val binding: RowListItemBinding = RowListItemBinding.inflate(LayoutInflater.from(group.getContext()), group, false)
            binding.getRoot().setSaveFromParentEnabled(false)
            group.addView(binding.getRoot())
            return ListRow(binding)
        }
    }
}
