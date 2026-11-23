package com.ditzzy.dsunext.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import com.ditzzy.dsunext.databinding.RowTextInputBinding
import java.util.function.Consumer

/** A segmented list row that holds a numeric text field. */
class InputRow {
    private val binding: RowTextInputBinding

    private var suppressWatcher: Boolean = false

    private constructor(binding: RowTextInputBinding) {
        this.binding = binding
    }

    fun onTextChanged(listener: Consumer<String>): InputRow {
        binding.inputEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {

            }

            override fun afterTextChanged(s: Editable) {
                if (!suppressWatcher) {
                    listener.accept(s.toString())
                }
            }
        })
        return this
    }

    /** Updates the field without echoing the change back to the listener. */
    fun setText(text: String) {
        if (((binding.inputEdit.getText() != null) && binding.inputEdit.getText()!!.toString().equals(text))) {
            return
        }
        suppressWatcher = true
        binding.inputEdit.setText(text)
        binding.inputEdit.setSelection(text.length)
        suppressWatcher = false
    }

    fun setError(error: CharSequence?) {
        binding.inputLayout.setError(error)
    }

    fun setVisible(visible: Boolean) {
        binding.getRoot().setVisibility((if (visible) View.VISIBLE else View.GONE))
    }

    fun isVisible(): Boolean {
        return (binding.getRoot().getVisibility() == View.VISIBLE)
    }

    companion object {
        @JvmStatic
        fun create(group: LinearLayout, @StringRes hint: Int, suffix: String): InputRow {
            val binding: RowTextInputBinding = RowTextInputBinding.inflate(LayoutInflater.from(group.getContext()), group, false)
            binding.inputLayout.setHint(hint)
            binding.inputLayout.setSuffixText(suffix)
            group.addView(binding.getRoot())
            binding.getRoot().setVisibility(View.GONE)
            return InputRow(binding)
        }
    }
}
