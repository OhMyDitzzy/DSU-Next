package com.ditzzy.dsunext.activity

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.checker.treble.ABResult
import com.ditzzy.dsunext.checker.treble.ArchitectureResult
import com.ditzzy.dsunext.checker.treble.TrebleReport
import com.ditzzy.dsunext.checker.treble.TrebleResult
import com.ditzzy.dsunext.databinding.ActivityTrebleBinding
import com.ditzzy.dsunext.ui.InsetsUtils
import com.ditzzy.dsunext.ui.ListRow
import com.ditzzy.dsunext.ui.SegmentedGroup
import com.ditzzy.dsunext.viewmodel.TrebleViewModel
import com.ditzzy.dsunext.checker.treble.ABI

open class TrebleActivity : AppCompatActivity() {
    private var binding: ActivityTrebleBinding? = null

    protected override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityTrebleBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        binding!!.toolbar.setNavigationOnClickListener({ v ->
            finish()
        })
        InsetsUtils.padBottomAndSides(binding!!.scrollView)
        val viewModel: TrebleViewModel = ViewModelProvider(this).`get`(TrebleViewModel::class.java)
        viewModel.getReport().observe(this, this::renderReport)
        viewModel.load()
    }

    private fun renderReport(report: TrebleReport?) {
        if ((report == null)) {
            return
        }
        val supported: Boolean = report!!.isTrebleSupported
        val title: String = getString((if (supported) R.string.treble_supported else R.string.treble_not_supported))
        binding!!.trebleLoading.setVisibility(View.GONE)
        binding!!.trebleStatus.setVisibility(View.VISIBLE)
        binding!!.trebleStatus.setContentDescription(title)
        binding!!.trebleStatus.setStatus(supported)
        binding!!.trebleTitle.setText(title)
        binding!!.trebleSummary.setText((if (supported) R.string.treble_supported_summary else R.string.treble_not_supported_summary))
        binding!!.trebleSummary.setVisibility(View.VISIBLE)
        renderDetails(report!!)
        fadeIn(binding!!.trebleTitle, TEXT_FADE_DELAY_MS)
        fadeIn(binding!!.trebleSummary, TEXT_FADE_DELAY_MS)
        fadeIn(binding!!.detailsSection, DETAILS_FADE_DELAY_MS)
    }

    private fun renderDetails(report: TrebleReport) {
        val group: LinearLayout = binding!!.groupDetails
        group.removeAllViews()
        val treble: TrebleResult? = report.treble
        if ((treble != null)) {
            addRow(group, R.drawable.ic_info, R.string.treble_type, getString((if (treble!!.isTrebleLegacy) R.string.treble_type_legacy else R.string.treble_type_standard)))
            val vndk: String? = treble!!.vndkVersion
            addRow(group, R.drawable.ic_code, R.string.treble_vndk_version, (if ((vndk != null)) vndk!! else getString(R.string.treble_unknown)))
            addRow(group, R.drawable.ic_bolt, R.string.treble_vndk_lite, getString((if (treble!!.isVndkLite) R.string.yes else R.string.no)))
        }
        addRow(group, R.drawable.ic_phone, R.string.treble_cpu_architecture, architectureLabel(report.architecture))
        addRow(group, R.drawable.ic_storage, R.string.treble_partition_scheme, partitionLabel(report.ab))
        addRow(group, R.drawable.ic_sd_card, R.string.treble_system_as_root, yesNoUnknown(report.systemAsRoot))
        SegmentedGroup.apply(group)
        binding!!.detailsSection.setVisibility(View.VISIBLE)
    }

    private fun addRow(group: LinearLayout, icon: Int, @StringRes title: Int, value: CharSequence) {
        ListRow.create(group).icon(icon).title(title).supporting(value).notClickable()
    }

    private fun architectureLabel(result: ArchitectureResult?): String {
        if ((result == null)) {
            return getString(R.string.treble_unknown)
        }
        when (result!!.cpuArch) {
            ABI.ARM32 -> {
                return getString(R.string.treble_arch_arm32)
            }
            ABI.ARM32_BINDER64 -> {
                return getString(R.string.treble_arch_arm32_binder64)
            }
            ABI.ARM64 -> {
                return getString(R.string.treble_arch_arm64)
            }
            ABI.X86 -> {
                return getString(R.string.treble_arch_x86)
            }
            ABI.X86_64 -> {
                return getString(R.string.treble_arch_x86_64)
            }
            else -> {
                return getString(R.string.treble_unknown)
            }
        }
    }

    private fun partitionLabel(result: ABResult?): String {
        if ((result == null)) {
            return getString(R.string.treble_partition_a_only)
        }
        return getString((if (result!!.isVirtual) R.string.treble_partition_virtual_ab else R.string.treble_partition_ab))
    }

    private fun yesNoUnknown(value: Boolean?): String {
        if ((value == null)) {
            return getString(R.string.treble_unknown)
        }
        return getString((if (value) R.string.yes else R.string.no))
    }

    protected override fun onDestroy() {
        super.onDestroy()
        binding = null
    }

    companion object {
        private const val TEXT_FADE_DELAY_MS: Long = 350L

        private const val DETAILS_FADE_DELAY_MS: Long = 500L

        private const val FADE_DURATION_MS: Long = 300L

        private fun fadeIn(view: View, delay: Long) {
            view.setAlpha(0f)
            view.animate().alpha(1f).setStartDelay(delay).setDuration(FADE_DURATION_MS).start()
        }
    }
}
