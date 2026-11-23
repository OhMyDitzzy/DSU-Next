package com.ditzzy.dsunext.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.DialogLanguageBinding
import com.ditzzy.dsunext.databinding.RowLanguageOptionBinding
import com.ditzzy.dsunext.model.AppLanguage
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.RelativeCornerSize
import java.util.EnumMap

/**
 * Language picker. The options are tonal cards that morph into pills when selected, with springy
 * motion, which is how Material 3 Expressive shows a selection.
 */
class LanguageDialog {
    fun interface Listener {
        fun onLanguageSelected(language: AppLanguage)
    }

    private val context: Context

    private val listener: Listener

    private val systemLanguage: AppLanguage

    private val rows: MutableMap<AppLanguage, RowLanguageOptionBinding> = EnumMap<AppLanguage, RowLanguageOptionBinding>(AppLanguage::class.java)

    private val animators: MutableMap<AppLanguage, ValueAnimator> = EnumMap<AppLanguage, ValueAnimator>(AppLanguage::class.java)

    private val handler: Handler = Handler(Looper.getMainLooper())

    private val argb: ArgbEvaluator = ArgbEvaluator()

    private val idleBackground: Int

    private val selectedBackground: Int

    private val idleTitle: Int

    private val selectedText: Int

    private val idleSupporting: Int

    private var selected: AppLanguage? = null

    private var closing: Boolean = false

    private var dialog: androidx.appcompat.app.AlertDialog? = null

    private constructor(context: Context, current: AppLanguage, systemLanguage: AppLanguage, listener: Listener) {
        this.context = context
        this.selected = current
        this.systemLanguage = systemLanguage
        this.listener = listener
        idleBackground = color(context, com.google.android.material.R.attr.colorSurfaceContainerHighest)
        selectedBackground = color(context, com.google.android.material.R.attr.colorSecondaryContainer)
        idleTitle = color(context, com.google.android.material.R.attr.colorOnSurface)
        selectedText = color(context, com.google.android.material.R.attr.colorOnSecondaryContainer)
        idleSupporting = color(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    private fun show() {
        val builder: MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(context)
        val inflater: LayoutInflater = LayoutInflater.from(builder.getContext())
        val content: DialogLanguageBinding = DialogLanguageBinding.inflate(inflater)
        val gap: Int = context.getResources().getDimensionPixelSize(R.dimen.segmented_item_gap)
        for (language in AppLanguage.values()) {
            val row: RowLanguageOptionBinding = RowLanguageOptionBinding.inflate(inflater, content.languageOptions, false)
            bind(row, language)
            val params: LinearLayout.LayoutParams = (row.getRoot().getLayoutParams() as LinearLayout.LayoutParams)
            params.topMargin = (if ((language.ordinal == 0)) 0 else gap)
            row.getRoot().setLayoutParams(params)
            row.getRoot().setAlpha(0f)
            content.languageOptions.addView(row.getRoot())
            rows.put(language, row)
        }
        dialog = builder.setIcon(R.drawable.ic_language).setTitle(R.string.language).setView(content.getRoot()).setNegativeButton(R.string.cancel, null).create()
        dialog!!.setOnShowListener({ d ->
            playEnter(content.languageOptions)
        })
        dialog!!.setOnDismissListener({ d ->
            cancelAnimations()
        })
        dialog!!.show()
    }

    private fun bind(row: RowLanguageOptionBinding, language: AppLanguage) {
        row.optionTitle.setText(language.getLabel())
        val supporting: CharSequence = supportingText(language)
        row.optionSupporting.setText(supporting)
        row.optionSupporting.setVisibility((if ((supporting.length == 0)) View.GONE else View.VISIBLE))
        applyProgress(row, (if ((language == selected)) 1f else 0f))
        applyAccessibility(row, (language == selected))
        row.optionCard.setOnClickListener({ v ->
            choose(language)
        })
        attachPressFeedback(row.optionCard)
    }

    private fun supportingText(language: AppLanguage): CharSequence {
        if (language.isSystem()) {
            return context.getString(R.string.language_system_current, context.getString(systemLanguage.getLabel()))
        }
        val name: String = context.getString(language.getLocalizedName())
        // "English" under "English" says nothing
        return (if (name.equals(context.getString(language.getLabel()), ignoreCase = true)) "" else name)
    }

    private fun choose(language: AppLanguage) {
        if (closing) {
            return
        }
        if ((language == selected)) {
            dialog!!.dismiss()
            return
        }
        closing = true
        val previous: AppLanguage = selected!!
        selected = language
        morph(previous, false)
        morph(language, true)
        handler.postDelayed({
            if (dialog!!.isShowing()) {
                dialog!!.dismiss()
            }
            listener.onLanguageSelected(language)
        }, DISMISS_DELAY_MS)
    }

    private fun morph(language: AppLanguage, toSelected: Boolean) {
        val row: RowLanguageOptionBinding? = rows.`get`(language)
        if ((row == null)) {
            return
        }
        val running: ValueAnimator? = animators.`get`(language)
        if ((running != null)) {
            running!!.cancel()
        }
        val animator: ValueAnimator = ValueAnimator.ofFloat((if (toSelected) 0f else 1f), (if (toSelected) 1f else 0f))
        animator.setDuration(MORPH_MS)
        animator.setInterpolator(SPATIAL)
        animator.addUpdateListener({ a ->
            applyProgress(row!!, (a.getAnimatedValue() as Float))
        })
        animators.put(language, animator)
        applyAccessibility(row!!, toSelected)
        animator.start()
    }

    /** 0 is an idle card, 1 a selected one. The spring may push it a little past both ends. */
    private fun applyProgress(row: RowLanguageOptionBinding, progress: Float) {
        val clamped: Float = Math.max(0f, Math.min(1f, progress))
        val corner: Float = (CORNER_IDLE + ((CORNER_SELECTED - CORNER_IDLE) * progress))
        row.optionCard.setShapeAppearanceModel(row.optionCard.getShapeAppearanceModel().toBuilder().setAllCornerSizes(RelativeCornerSize(Math.max(0f, Math.min(0.5f, corner)))).build())
        row.optionCard.setCardBackgroundColor((argb.evaluate(clamped, idleBackground, selectedBackground) as Int))
        row.optionTitle.setTextColor((argb.evaluate(clamped, idleTitle, selectedText) as Int))
        row.optionSupporting.setTextColor((argb.evaluate(clamped, idleSupporting, selectedText) as Int))
        row.optionCheck.setVisibility((if ((clamped > 0f)) View.VISIBLE else View.INVISIBLE))
        row.optionCheck.setAlpha(clamped)
        val checkScale: Float = (0.5f + (0.5f * Math.max(0f, progress)))
        row.optionCheck.setScaleX(checkScale)
        row.optionCheck.setScaleY(checkScale)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressFeedback(card: View) {
        // Returns false so the click and the ripple still work
        card.setOnTouchListener(callback6@ { v, event ->
            val action: Int = event.getActionMasked()
            if ((action == MotionEvent.ACTION_DOWN)) {
                v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE).setDuration(PRESS_MS).setInterpolator(SPATIAL).start()
            } else if (((action == MotionEvent.ACTION_UP) || (action == MotionEvent.ACTION_CANCEL))) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(RELEASE_MS).setInterpolator(SPATIAL).start()
            }
            return@callback6 false
        })
    }

    private fun playEnter(options: ViewGroup) {
        val offset: Float = (ENTER_OFFSET_DP * context.getResources().getDisplayMetrics().density)
        run {
            var i: Int = 0
            while ((i < options.getChildCount())) {
                val child: View = options.getChildAt(i)!!
                child.setAlpha(0f)
                child.setTranslationY(offset)
                child.animate().alpha(1f).translationY(0f).setStartDelay(((i).toLong() * ENTER_STAGGER_MS)).setDuration(ENTER_MS).setInterpolator(SPATIAL).start()
                i++
            }
        }
    }

    private fun cancelAnimations() {
        for (animator in animators.values) {
            animator.cancel()
        }
        animators.clear()
    }

    /** The cards behave as radio buttons, so screen readers announce them that way. */
    private fun applyAccessibility(row: RowLanguageOptionBinding, checked: Boolean) {
        ViewCompat.setAccessibilityDelegate(row.optionCard, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.setClassName(RadioButton::class.java.getName())
                info.setCheckable(true)
                info.setChecked(checked)
            }
        })
    }

    companion object {
        /** Fast spatial spring: leaves the start quickly and settles with a small overshoot. */
        private val SPATIAL: PathInterpolator = PathInterpolator(0.38f, 1.21f, 0.22f, 1f)

        private const val MORPH_MS: Long = 380L

        private const val PRESS_MS: Long = 120L

        private const val RELEASE_MS: Long = 300L

        private const val ENTER_MS: Long = 420L

        private const val ENTER_STAGGER_MS: Long = 50L

        /** Long enough to see the selection morph before the dialog goes away and the screen is recreated. */
        private const val DISMISS_DELAY_MS: Long = 280L

        private const val PRESSED_SCALE: Float = 0.97f

        /** Corner size relative to the card height, 0.5 is a full pill. */
        private const val CORNER_IDLE: Float = 0.22f

        private const val CORNER_SELECTED: Float = 0.5f

        private const val ENTER_OFFSET_DP: Float = 20f

        /**
         * @param current        the language that is selected now
         * @param systemLanguage the language the app uses while it follows the system, shown on that option
         */
        @JvmStatic
        fun show(context: Context, current: AppLanguage, systemLanguage: AppLanguage, listener: Listener) {
            LanguageDialog(context, current, systemLanguage, listener).show()
        }

        private fun color(context: Context, attr: Int): Int {
            return MaterialColors.getColor(context, attr, 0)
        }
    }
}
