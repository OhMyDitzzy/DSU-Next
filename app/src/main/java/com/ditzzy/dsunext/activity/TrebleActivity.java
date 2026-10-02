package com.ditzzy.dsunext.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.checker.treble.ABResult;
import com.ditzzy.dsunext.checker.treble.ArchitectureResult;
import com.ditzzy.dsunext.checker.treble.TrebleReport;
import com.ditzzy.dsunext.checker.treble.TrebleResult;
import com.ditzzy.dsunext.databinding.ActivityTrebleBinding;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.ListRow;
import com.ditzzy.dsunext.ui.SegmentedGroup;
import com.ditzzy.dsunext.viewmodel.TrebleViewModel;

public class TrebleActivity extends AppCompatActivity {

    private static final long TEXT_FADE_DELAY_MS = 350L;
    private static final long DETAILS_FADE_DELAY_MS = 500L;
    private static final long FADE_DURATION_MS = 300L;

    private ActivityTrebleBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityTrebleBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.padBottomAndSides(binding.scrollView);

        TrebleViewModel viewModel = new ViewModelProvider(this).get(TrebleViewModel.class);
        viewModel.getReport().observe(this, this::renderReport);
        viewModel.load();
    }

    private void renderReport(@Nullable TrebleReport report) {
        if (report == null) {
            return;
        }
        boolean supported = report.isTrebleSupported();
        String title = getString(supported ? R.string.treble_supported : R.string.treble_not_supported);

        binding.trebleLoading.setVisibility(View.GONE);
        binding.trebleStatus.setVisibility(View.VISIBLE);
        binding.trebleStatus.setContentDescription(title);
        binding.trebleStatus.setStatus(supported);

        binding.trebleTitle.setText(title);
        binding.trebleSummary.setText(supported
                ? R.string.treble_supported_summary
                : R.string.treble_not_supported_summary);
        binding.trebleSummary.setVisibility(View.VISIBLE);

        renderDetails(report);

        fadeIn(binding.trebleTitle, TEXT_FADE_DELAY_MS);
        fadeIn(binding.trebleSummary, TEXT_FADE_DELAY_MS);
        fadeIn(binding.detailsSection, DETAILS_FADE_DELAY_MS);
    }

    private void renderDetails(TrebleReport report) {
        LinearLayout group = binding.groupDetails;
        group.removeAllViews();

        TrebleResult treble = report.getTreble();
        if (treble != null) {
            addRow(group, R.drawable.ic_info, R.string.treble_type,
                    getString(treble.isTrebleLegacy()
                            ? R.string.treble_type_legacy
                            : R.string.treble_type_standard));
            String vndk = treble.getVndkVersion();
            addRow(group, R.drawable.ic_code, R.string.treble_vndk_version,
                    vndk != null ? vndk : getString(R.string.treble_unknown));
            addRow(group, R.drawable.ic_bolt, R.string.treble_vndk_lite,
                    getString(treble.isVndkLite() ? R.string.yes : R.string.no));
        }

        addRow(group, R.drawable.ic_phone, R.string.treble_cpu_architecture,
                architectureLabel(report.getArchitecture()));
        addRow(group, R.drawable.ic_storage, R.string.treble_partition_scheme,
                partitionLabel(report.getAb()));
        addRow(group, R.drawable.ic_sd_card, R.string.treble_system_as_root,
                yesNoUnknown(report.getSystemAsRoot()));

        SegmentedGroup.apply(group);
        binding.detailsSection.setVisibility(View.VISIBLE);
    }

    private void addRow(LinearLayout group, int icon, @StringRes int title, CharSequence value) {
        ListRow.create(group)
                .icon(icon)
                .title(title)
                .supporting(value)
                .notClickable();
    }

    private String architectureLabel(@Nullable ArchitectureResult result) {
        if (result == null) {
            return getString(R.string.treble_unknown);
        }
        switch (result.getCpuArch()) {
            case ARM32:
                return getString(R.string.treble_arch_arm32);
            case ARM32_BINDER64:
                return getString(R.string.treble_arch_arm32_binder64);
            case ARM64:
                return getString(R.string.treble_arch_arm64);
            case X86:
                return getString(R.string.treble_arch_x86);
            case X86_64:
                return getString(R.string.treble_arch_x86_64);
            default:
                return getString(R.string.treble_unknown);
        }
    }

    // A missing result means the device has no A/B partitions at all
    private String partitionLabel(@Nullable ABResult result) {
        if (result == null) {
            return getString(R.string.treble_partition_a_only);
        }
        return getString(result.isVirtual()
                ? R.string.treble_partition_virtual_ab
                : R.string.treble_partition_ab);
    }

    private String yesNoUnknown(@Nullable Boolean value) {
        if (value == null) {
            return getString(R.string.treble_unknown);
        }
        return getString(value ? R.string.yes : R.string.no);
    }

    private static void fadeIn(View view, long delay) {
        view.setAlpha(0f);
        view.animate()
                .alpha(1f)
                .setStartDelay(delay)
                .setDuration(FADE_DURATION_MS)
                .start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
