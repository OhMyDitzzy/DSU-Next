package com.ditzzy.dsunext.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.ditzzy.dsunext.DsuNextApp
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.ActivityAgreementBinding
import com.ditzzy.dsunext.databinding.RowTextBlockBinding
import com.ditzzy.dsunext.ui.InsetsUtils
import com.ditzzy.dsunext.ui.SegmentedGroup

/**
 * Shows the user agreement. When started through [#createRequiredIntent] it has to be
 * accepted (the result is RESULT_OK), otherwise it is a read-only page.
 */
open class AgreementActivity : AppCompatActivity() {
    private var binding: ActivityAgreementBinding? = null

    private var requireAccept: Boolean = false

    private var reachedEnd: Boolean = false

    protected override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAgreementBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        requireAccept = getIntent().getBooleanExtra(EXTRA_REQUIRE_ACCEPT, false)
        reachedEnd = ((savedInstanceState != null) && savedInstanceState!!.getBoolean(STATE_REACHED_END))
        addSection(R.string.agreement_who_title, R.string.agreement_who_body)
        addSection(R.string.agreement_risks_title, R.string.agreement_risks_body)
        addSection(R.string.agreement_preparation_title, R.string.agreement_preparation_body)
        addSection(R.string.agreement_responsibility_title, R.string.agreement_responsibility_body)
        addSection(R.string.agreement_asis_title, R.string.agreement_asis_body)
        addSection(R.string.agreement_liability_title, R.string.agreement_liability_body)
        addSection(R.string.agreement_third_party_title, R.string.agreement_third_party_body)
        addSection(R.string.agreement_accept_title, R.string.agreement_accept_body)
        SegmentedGroup.apply(binding!!.sectionsGroup)
        InsetsUtils.padSides(binding!!.scrollView)
        if (requireAccept) {
            setupRequiredMode()
        } else {
            binding!!.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            binding!!.toolbar.setNavigationContentDescription(R.string.navigate_up)
            binding!!.toolbar.setNavigationOnClickListener({ v ->
                finish()
            })
        }
    }

    private fun addSection(@StringRes title: Int, @StringRes body: Int) {
        val block: RowTextBlockBinding = RowTextBlockBinding.inflate(LayoutInflater.from(this), binding!!.sectionsGroup, false)
        block.blockTitle.setText(title)
        block.blockBody.setText(body)
        binding!!.sectionsGroup.addView(block.getRoot())
    }

    private fun setupRequiredMode() {
        binding!!.bottomBar.setVisibility(View.VISIBLE)
        InsetsUtils.padBottomAndSides(binding!!.bottomBar)
        binding!!.buttonAgree.setOnClickListener({ v ->
            DsuNextApp.from(this).getAppPrefs().acceptUserAgreement()
            setResult(RESULT_OK)
            finish()
        })
        binding!!.buttonDecline.setOnClickListener({ v ->
            decline()
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                decline()
            }
        })
        binding!!.scrollView.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
            updateAcceptState()
        })
        binding!!.scrollView.addOnLayoutChangeListener({ v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            updateAcceptState()
        })
        updateAcceptState()
    }

    private fun updateAcceptState() {
        if (!binding!!.scrollView.canScrollVertically(1)) {
            reachedEnd = true
        }
        binding!!.buttonAgree.setEnabled(reachedEnd)
        binding!!.scrollHint.setVisibility((if (reachedEnd) View.INVISIBLE else View.VISIBLE))
    }

    private fun decline() {
        setResult(RESULT_CANCELED)
        finish()
    }

    protected override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REACHED_END, reachedEnd)
    }

    protected override fun onDestroy() {
        super.onDestroy()
        binding = null
    }

    companion object {
        private const val EXTRA_REQUIRE_ACCEPT: String = "extra_require_accept"

        private const val STATE_REACHED_END: String = "state_reached_end"

        @JvmStatic
        fun createRequiredIntent(context: Context): Intent {
            return Intent(context, AgreementActivity::class.java).putExtra(EXTRA_REQUIRE_ACCEPT, true)
        }

        @JvmStatic
        fun createReadOnlyIntent(context: Context): Intent {
            return Intent(context, AgreementActivity::class.java)
        }
    }
}
