package com.ditzzy.dsunext.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.RadioButton;

import androidx.annotation.NonNull;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.DialogLanguageBinding;
import com.ditzzy.dsunext.databinding.RowLanguageOptionBinding;
import com.ditzzy.dsunext.model.AppLanguage;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.RelativeCornerSize;

import java.util.EnumMap;
import java.util.Map;

/**
 * Language picker. The options are tonal cards that morph into pills when selected, with springy
 * motion, which is how Material 3 Expressive shows a selection.
 */
public final class LanguageDialog {

    public interface Listener {
        void onLanguageSelected(AppLanguage language);
    }

    /** Fast spatial spring: leaves the start quickly and settles with a small overshoot. */
    private static final PathInterpolator SPATIAL = new PathInterpolator(0.38f, 1.21f, 0.22f, 1f);

    private static final long MORPH_MS = 380L;
    private static final long PRESS_MS = 120L;
    private static final long RELEASE_MS = 300L;
    private static final long ENTER_MS = 420L;
    private static final long ENTER_STAGGER_MS = 50L;
    /** Long enough to see the selection morph before the dialog goes away and the screen is recreated. */
    private static final long DISMISS_DELAY_MS = 280L;

    private static final float PRESSED_SCALE = 0.97f;
    /** Corner size relative to the card height, 0.5 is a full pill. */
    private static final float CORNER_IDLE = 0.22f;
    private static final float CORNER_SELECTED = 0.5f;
    private static final float ENTER_OFFSET_DP = 20f;

    private final Context context;
    private final Listener listener;
    private final AppLanguage systemLanguage;
    private final Map<AppLanguage, RowLanguageOptionBinding> rows = new EnumMap<>(AppLanguage.class);
    private final Map<AppLanguage, ValueAnimator> animators = new EnumMap<>(AppLanguage.class);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArgbEvaluator argb = new ArgbEvaluator();

    private final int idleBackground;
    private final int selectedBackground;
    private final int idleTitle;
    private final int selectedText;
    private final int idleSupporting;

    private AppLanguage selected;
    private boolean closing;
    private androidx.appcompat.app.AlertDialog dialog;

    private LanguageDialog(Context context, AppLanguage current, AppLanguage systemLanguage, Listener listener) {
        this.context = context;
        this.selected = current;
        this.systemLanguage = systemLanguage;
        this.listener = listener;
        idleBackground = color(context, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        selectedBackground = color(context, com.google.android.material.R.attr.colorSecondaryContainer);
        idleTitle = color(context, com.google.android.material.R.attr.colorOnSurface);
        selectedText = color(context, com.google.android.material.R.attr.colorOnSecondaryContainer);
        idleSupporting = color(context, com.google.android.material.R.attr.colorOnSurfaceVariant);
    }

    /**
     * @param current        the language that is selected now
     * @param systemLanguage the language the app uses while it follows the system, shown on that option
     */
    public static void show(Context context, AppLanguage current, AppLanguage systemLanguage, Listener listener) {
        new LanguageDialog(context, current, systemLanguage, listener).show();
    }

    private void show() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        LayoutInflater inflater = LayoutInflater.from(builder.getContext());
        DialogLanguageBinding content = DialogLanguageBinding.inflate(inflater);

        int gap = context.getResources().getDimensionPixelSize(R.dimen.segmented_item_gap);
        for (AppLanguage language : AppLanguage.values()) {
            RowLanguageOptionBinding row = RowLanguageOptionBinding.inflate(inflater, content.languageOptions, false);
            bind(row, language);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) row.getRoot().getLayoutParams();
            params.topMargin = language.ordinal() == 0 ? 0 : gap;
            row.getRoot().setLayoutParams(params);
            // Hidden until the enter animation starts, so the first frame does not flash
            row.getRoot().setAlpha(0f);
            content.languageOptions.addView(row.getRoot());
            rows.put(language, row);
        }

        dialog = builder
                .setIcon(R.drawable.ic_language)
                .setTitle(R.string.language)
                .setView(content.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> playEnter(content.languageOptions));
        dialog.setOnDismissListener(d -> cancelAnimations());
        dialog.show();
    }

    private void bind(RowLanguageOptionBinding row, AppLanguage language) {
        row.optionTitle.setText(language.getLabel());

        CharSequence supporting = supportingText(language);
        row.optionSupporting.setText(supporting);
        row.optionSupporting.setVisibility(supporting.length() == 0 ? View.GONE : View.VISIBLE);

        applyProgress(row, language == selected ? 1f : 0f);
        applyAccessibility(row, language == selected);
        row.optionCard.setOnClickListener(v -> choose(language));
        attachPressFeedback(row.optionCard);
    }

    private CharSequence supportingText(AppLanguage language) {
        if (language.isSystem()) {
            return context.getString(R.string.language_system_current, context.getString(systemLanguage.getLabel()));
        }
        String name = context.getString(language.getLocalizedName());
        // "English" under "English" says nothing
        return name.equalsIgnoreCase(context.getString(language.getLabel())) ? "" : name;
    }

    private void choose(AppLanguage language) {
        if (closing) {
            return;
        }
        if (language == selected) {
            dialog.dismiss();
            return;
        }
        closing = true;
        AppLanguage previous = selected;
        selected = language;
        morph(previous, false);
        morph(language, true);
        handler.postDelayed(() -> {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            listener.onLanguageSelected(language);
        }, DISMISS_DELAY_MS);
    }

    private void morph(AppLanguage language, boolean toSelected) {
        RowLanguageOptionBinding row = rows.get(language);
        if (row == null) {
            return;
        }
        ValueAnimator running = animators.get(language);
        if (running != null) {
            running.cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(toSelected ? 0f : 1f, toSelected ? 1f : 0f);
        animator.setDuration(MORPH_MS);
        animator.setInterpolator(SPATIAL);
        animator.addUpdateListener(a -> applyProgress(row, (Float) a.getAnimatedValue()));
        animators.put(language, animator);
        applyAccessibility(row, toSelected);
        animator.start();
    }

    /** 0 is an idle card, 1 a selected one. The spring may push it a little past both ends. */
    private void applyProgress(RowLanguageOptionBinding row, float progress) {
        float clamped = Math.max(0f, Math.min(1f, progress));

        float corner = CORNER_IDLE + (CORNER_SELECTED - CORNER_IDLE) * progress;
        row.optionCard.setShapeAppearanceModel(row.optionCard.getShapeAppearanceModel().toBuilder()
                .setAllCornerSizes(new RelativeCornerSize(Math.max(0f, Math.min(0.5f, corner))))
                .build());
        row.optionCard.setCardBackgroundColor((Integer) argb.evaluate(clamped, idleBackground, selectedBackground));
        row.optionTitle.setTextColor((Integer) argb.evaluate(clamped, idleTitle, selectedText));
        row.optionSupporting.setTextColor((Integer) argb.evaluate(clamped, idleSupporting, selectedText));

        row.optionCheck.setVisibility(clamped > 0f ? View.VISIBLE : View.INVISIBLE);
        row.optionCheck.setAlpha(clamped);
        float checkScale = 0.5f + 0.5f * Math.max(0f, progress);
        row.optionCheck.setScaleX(checkScale);
        row.optionCheck.setScaleY(checkScale);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachPressFeedback(View card) {
        // Returns false so the click and the ripple still work
        card.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                        .setDuration(PRESS_MS).setInterpolator(SPATIAL).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(RELEASE_MS).setInterpolator(SPATIAL).start();
            }
            return false;
        });
    }

    private void playEnter(ViewGroup options) {
        float offset = ENTER_OFFSET_DP * context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < options.getChildCount(); i++) {
            View child = options.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(offset);
            child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * ENTER_STAGGER_MS)
                    .setDuration(ENTER_MS)
                    .setInterpolator(SPATIAL)
                    .start();
        }
    }

    private void cancelAnimations() {
        for (ValueAnimator animator : animators.values()) {
            animator.cancel();
        }
        animators.clear();
    }

    /** The cards behave as radio buttons, so screen readers announce them that way. */
    private void applyAccessibility(RowLanguageOptionBinding row, boolean checked) {
        ViewCompat.setAccessibilityDelegate(row.optionCard, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(checked);
            }
        });
    }

    private static int color(Context context, int attr) {
        return MaterialColors.getColor(context, attr, 0);
    }
}
