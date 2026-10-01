package com.ditzzy.dsunext.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.ditzzy.dsunext.DsuNextApp;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityAgreementBinding;
import com.ditzzy.dsunext.databinding.RowTextBlockBinding;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.SegmentedGroup;

/**
 * Shows the user agreement. When started through {@link #createRequiredIntent} it has to be
 * accepted (the result is RESULT_OK), otherwise it is a read-only page.
 */
public class AgreementActivity extends AppCompatActivity {

    private static final String EXTRA_REQUIRE_ACCEPT = "extra_require_accept";
    private static final String STATE_REACHED_END = "state_reached_end";

    private ActivityAgreementBinding binding;
    private boolean requireAccept = false;
    private boolean reachedEnd = false;

    public static Intent createRequiredIntent(Context context) {
        return new Intent(context, AgreementActivity.class).putExtra(EXTRA_REQUIRE_ACCEPT, true);
    }

    public static Intent createReadOnlyIntent(Context context) {
        return new Intent(context, AgreementActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityAgreementBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        requireAccept = getIntent().getBooleanExtra(EXTRA_REQUIRE_ACCEPT, false);
        reachedEnd = savedInstanceState != null && savedInstanceState.getBoolean(STATE_REACHED_END);

        addSection(R.string.agreement_who_title, R.string.agreement_who_body);
        addSection(R.string.agreement_risks_title, R.string.agreement_risks_body);
        addSection(R.string.agreement_preparation_title, R.string.agreement_preparation_body);
        addSection(R.string.agreement_responsibility_title, R.string.agreement_responsibility_body);
        addSection(R.string.agreement_asis_title, R.string.agreement_asis_body);
        addSection(R.string.agreement_liability_title, R.string.agreement_liability_body);
        addSection(R.string.agreement_third_party_title, R.string.agreement_third_party_body);
        addSection(R.string.agreement_accept_title, R.string.agreement_accept_body);
        SegmentedGroup.apply(binding.sectionsGroup);

        InsetsUtils.padSides(binding.scrollView);

        if (requireAccept) {
            setupRequiredMode();
        } else {
            binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            binding.toolbar.setNavigationContentDescription(R.string.navigate_up);
            binding.toolbar.setNavigationOnClickListener(v -> finish());
        }
    }

    private void addSection(@StringRes int title, @StringRes int body) {
        RowTextBlockBinding block =
                RowTextBlockBinding.inflate(LayoutInflater.from(this), binding.sectionsGroup, false);
        block.blockTitle.setText(title);
        block.blockBody.setText(body);
        binding.sectionsGroup.addView(block.getRoot());
    }

    private void setupRequiredMode() {
        binding.bottomBar.setVisibility(View.VISIBLE);
        InsetsUtils.padBottomAndSides(binding.bottomBar);

        binding.buttonAgree.setOnClickListener(v -> {
            DsuNextApp.from(this).getAppPrefs().acceptUserAgreement();
            setResult(RESULT_OK);
            finish();
        });
        binding.buttonDecline.setOnClickListener(v -> decline());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                decline();
            }
        });

        // The agreement can only be accepted once the whole page was reachable
        binding.scrollView.setOnScrollChangeListener(
                (androidx.core.widget.NestedScrollView.OnScrollChangeListener)
                        (v, scrollX, scrollY, oldScrollX, oldScrollY) -> updateAcceptState());
        binding.scrollView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateAcceptState());
        updateAcceptState();
    }

    private void updateAcceptState() {
        if (!binding.scrollView.canScrollVertically(1)) {
            reachedEnd = true;
        }
        binding.buttonAgree.setEnabled(reachedEnd);
        binding.scrollHint.setVisibility(reachedEnd ? View.INVISIBLE : View.VISIBLE);
    }

    private void decline() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_REACHED_END, reachedEnd);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}