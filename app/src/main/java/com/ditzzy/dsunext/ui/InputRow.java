package com.ditzzy.dsunext.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.ditzzy.dsunext.databinding.RowTextInputBinding;

import java.util.function.Consumer;

/** A segmented list row that holds a numeric text field. */
public final class InputRow {

    private final RowTextInputBinding binding;
    private boolean suppressWatcher = false;

    private InputRow(RowTextInputBinding binding) {
        this.binding = binding;
    }

    public static InputRow create(LinearLayout group, @StringRes int hint, String suffix) {
        RowTextInputBinding binding =
                RowTextInputBinding.inflate(LayoutInflater.from(group.getContext()), group, false);
        binding.inputLayout.setHint(hint);
        binding.inputLayout.setSuffixText(suffix);
        group.addView(binding.getRoot());
        binding.getRoot().setVisibility(View.GONE);
        return new InputRow(binding);
    }

    public InputRow onTextChanged(Consumer<String> listener) {
        binding.inputEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppressWatcher) {
                    listener.accept(s.toString());
                }
            }
        });
        return this;
    }

    /** Updates the field without echoing the change back to the listener. */
    public void setText(String text) {
        if (binding.inputEdit.getText() != null && binding.inputEdit.getText().toString().equals(text)) {
            return;
        }
        suppressWatcher = true;
        binding.inputEdit.setText(text);
        binding.inputEdit.setSelection(text.length());
        suppressWatcher = false;
    }

    public void setError(@Nullable CharSequence error) {
        binding.inputLayout.setError(error);
    }

    public void setVisible(boolean visible) {
        binding.getRoot().setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isVisible() {
        return binding.getRoot().getVisibility() == View.VISIBLE;
    }
}