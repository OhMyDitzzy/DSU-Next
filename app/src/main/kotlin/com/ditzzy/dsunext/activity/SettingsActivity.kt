package com.ditzzy.dsunext.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.ditzzy.dsunext.DsuNextApp
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.core.AppPrefs
import com.ditzzy.dsunext.core.LanguageManager
import com.ditzzy.dsunext.core.ThemeManager
import com.ditzzy.dsunext.databinding.ActivitySettingsBinding
import com.ditzzy.dsunext.model.OperationMode
import com.ditzzy.dsunext.model.Session
import com.ditzzy.dsunext.model.ThemeMode
import com.ditzzy.dsunext.ui.ColorSwatchRow
import com.ditzzy.dsunext.ui.InsetsUtils
import com.ditzzy.dsunext.ui.LanguageDialog
import com.ditzzy.dsunext.ui.ListRow
import com.ditzzy.dsunext.ui.SegmentedGroup
import com.ditzzy.dsunext.util.MultiGsi
import com.google.android.material.dialog.MaterialAlertDialogBuilder

open class SettingsActivity : AppCompatActivity() {
    private var binding: ActivitySettingsBinding? = null

    private var appPrefs: AppPrefs? = null

    private var session: Session? = null

    private var themeManager: ThemeManager? = null

    private var languageManager: LanguageManager? = null

    private var colorRow: ListRow? = null

    private var builtinRow: ListRow? = null

    private var multiGsiRow: ListRow? = null

    private var swatchRow: ColorSwatchRow? = null

    private var colorsExpanded: Boolean = false

    protected override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        val app: DsuNextApp = DsuNextApp.from(this)
        appPrefs = app.getAppPrefs()
        session = app.getSession()
        themeManager = app.getThemeManager()
        languageManager = app.getLanguageManager()
        // Recreating the activity after a color change must not fold the swatches back
        colorsExpanded = ((savedInstanceState != null) && savedInstanceState!!.getBoolean(STATE_COLORS_EXPANDED))
        binding!!.toolbar.setNavigationOnClickListener({ v ->
            finish()
        })
        InsetsUtils.padBottomAndSides(binding!!.scrollView)
        buildAppearanceGroup()
        buildInstallationGroup()
        buildDeveloperGroup()
        buildOtherGroup()
    }

    protected override fun onResume() {
        super.onResume()
        val visibility: Int = (if (appPrefs!!.getBoolean(AppPrefs.DEVELOPER_OPTIONS)) View.VISIBLE else View.GONE)
        binding!!.headerDeveloper.setVisibility(visibility)
        binding!!.groupDeveloper.setVisibility(visibility)
    }

    protected override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_COLORS_EXPANDED, colorsExpanded)
    }

    private fun buildAppearanceGroup() {
        val themeRow: ListRow = ListRow.create(binding!!.groupAppearance).icon(R.drawable.ic_brightness_medium).title(R.string.theme).trailingText(getString(themeManager!!.getThemeMode().getLabel()))
        themeRow.onClick({ v ->
            showThemeDialog(themeRow)
        })
        val dynamicSupported: Boolean = ThemeManager.isDynamicColorSupported()
        val dynamicRow: ListRow = ListRow.create(binding!!.groupAppearance).icon(R.drawable.ic_auto_awesome).title(R.string.dynamic_color).supporting((if (dynamicSupported) R.string.dynamic_color_description else R.string.dynamic_color_unsupported)).enabled(dynamicSupported)
        dynamicRow.toggle(themeManager!!.isDynamicColorEnabled(), Consumer { enabled ->
            themeManager!!.setDynamicColorEnabled(enabled)
            updateColorState()
        })
        colorRow = ListRow.create(binding!!.groupAppearance).icon(R.drawable.ic_palette).title(R.string.color).trailingIcon(R.drawable.ic_expand_more).onClick({ v ->
            setColorsExpanded(!colorsExpanded, true)
        })
        swatchRow = ColorSwatchRow.create(binding!!.groupAppearance, themeManager!!.getColorPalette(), { palette ->
            swatchRow!!.select(palette)
            themeManager!!.setColorPalette(palette)
            updateColorState()
        })
        val languageRow: ListRow = ListRow.create(binding!!.groupAppearance).icon(R.drawable.ic_language).title(R.string.language).trailingText(getString(languageManager!!.getLanguage().getLabel()))
        languageRow.onClick({ v ->
            showLanguageDialog(languageRow)
        })
        updateColorState()
        setColorsExpanded(colorsExpanded, false)
    }

    /** Swatches are dimmed and ignore taps while dynamic color is on. */
    private fun updateColorState() {
        val `dynamic`: Boolean = themeManager!!.isDynamicColorEnabled()
        swatchRow!!.enabled(!`dynamic`)
        colorRow!!.supporting((if (`dynamic`) R.string.color_disabled_dynamic else themeManager!!.getColorPalette().getLabel()))
    }

    private fun setColorsExpanded(expanded: Boolean, animate: Boolean) {
        colorsExpanded = expanded
        if (animate) {
            TransitionManager.beginDelayedTransition(binding!!.groupAppearance)
        }
        swatchRow!!.visible(expanded)
        // Corners of the segments depend on which rows are visible
        SegmentedGroup.apply(binding!!.groupAppearance)
        val rotation: Float = (if (expanded) 180f else 0f)
        if (animate) {
            colorRow!!.trailingIconView().animate().rotation(rotation).setDuration(200).start()
        } else {
            colorRow!!.trailingIconView().setRotation(rotation)
        }
    }

    private fun showThemeDialog(themeRow: ListRow) {
        val current: ThemeMode = themeManager!!.getThemeMode()
        val labels = Array<CharSequence>(THEME_CHOICES.size) { getString(THEME_CHOICES[it].getLabel()) }
        var checked: Int = 0
        run {
            var i: Int = 0
            while ((i < THEME_CHOICES.size)) {
                if ((THEME_CHOICES[i] == current)) {
                    checked = i
                }
                i++
            }
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.theme).setSingleChoiceItems(labels, checked, { dialog, which ->
            dialog.dismiss()
            val mode: ThemeMode = THEME_CHOICES[which]
            if ((mode != themeManager!!.getThemeMode())) {
                themeRow.trailingText(getString(mode.getLabel()))
                themeManager!!.setThemeMode(mode)
            }
        }).setNegativeButton(R.string.cancel, null).show()
    }

    private fun showLanguageDialog(languageRow: ListRow) {
        LanguageDialog.show(this, languageManager!!.getLanguage(), languageManager!!.getSystemLanguage(), callback9@ { language ->
            if ((language == languageManager!!.getLanguage())) {
                return@callback9
            }
            languageRow.trailingText(getString(language.getLabel()))
            languageManager!!.setLanguage(language)
        })
    }

    private fun buildInstallationGroup() {
        val builtinSupported: Boolean = (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q)
        val builtinAvailable: Boolean = (builtinSupported && session!!.isRoot())
        val builtin: ListRow = ListRow.create(binding!!.groupInstallation).icon(R.drawable.ic_bolt).title(R.string.builtin_installer).supporting((if (builtinSupported) (if (session!!.isRoot()) R.string.builtin_installer_description else R.string.requires_root) else R.string.builtin_installer_unsupported)).enabled(builtinAvailable)
        val builtinOn: Boolean = (builtinAvailable && appPrefs!!.getBoolean(AppPrefs.USE_BUILTIN_INSTALLER))
        builtinRow = builtin
        builtin.toggle(builtinOn, Consumer callback10@ { enabled ->
            if (!enabled) {
                appPrefs!!.setBoolean(AppPrefs.USE_BUILTIN_INSTALLER, false)
                appPrefs!!.setBoolean(AppPrefs.MULTI_GSI, false)
                if ((multiGsiRow != null)) {
                    multiGsiRow!!.checked(false)
                }
                return@callback10
            }
            confirm(R.drawable.ic_warning, R.string.experimental_feature, R.string.experimental_feature_description, Runnable {
                appPrefs!!.setBoolean(AppPrefs.USE_BUILTIN_INSTALLER, true)
            }, Runnable {
                builtin.checked(false)
            })
        })
        val multiSupported: Boolean = MultiGsi.isSupported(session!!)
        val multi: ListRow = ListRow.create(binding!!.groupInstallation).icon(R.drawable.ic_storage).title(R.string.multi_gsi).supporting((if (multiSupported) R.string.multi_gsi_setting_description else R.string.multi_gsi_requires_builtin)).enabled(multiSupported)
        multiGsiRow = multi
        multi.toggle((multiSupported && MultiGsi.isActive(appPrefs!!, session!!)), Consumer callback13@ { enabled ->
            if (!enabled) {
                appPrefs!!.setBoolean(AppPrefs.MULTI_GSI, false)
                return@callback13
            }
            confirm(R.drawable.ic_warning, R.string.multi_gsi_experimental_title, R.string.multi_gsi_enable_description, Runnable {
                appPrefs!!.setBoolean(AppPrefs.USE_BUILTIN_INSTALLER, true)
                appPrefs!!.setBoolean(AppPrefs.MULTI_GSI, true)
                builtinRow!!.checked(true)
            }, Runnable {
                multi.checked(false)
            })
        })
        toggleRow(binding!!.groupInstallation, R.drawable.ic_sd_card, R.string.unmount_sd_title, R.string.unmount_sd_description, AppPrefs.UMOUNT_SD)
        toggleRow(binding!!.groupInstallation, R.drawable.ic_phone, R.string.keep_screen_on, 0, AppPrefs.KEEP_SCREEN_ON)
        SegmentedGroup.apply(binding!!.groupInstallation)
    }

    private fun buildDeveloperGroup() {
        val storageCheck: ListRow = ListRow.create(binding!!.groupDeveloper).icon(R.drawable.ic_storage).title(R.string.storage_check_title).supporting(R.string.storage_check_description)
        storageCheck.toggle(appPrefs!!.getBoolean(AppPrefs.DISABLE_STORAGE_CHECK), Consumer callback16@ { disabled ->
            if (!disabled) {
                appPrefs!!.setBoolean(AppPrefs.DISABLE_STORAGE_CHECK, false)
                return@callback16
            }
            confirm(R.drawable.ic_warning, R.string.warning_storage_check_title, R.string.warning_storage_check_description, Runnable {
                appPrefs!!.setBoolean(AppPrefs.DISABLE_STORAGE_CHECK, true)
            }, Runnable {
                storageCheck.checked(false)
            })
        })
        val canLog: Boolean = (session!!.getOperationMode() != OperationMode.ADB)
        val fullLogcat: ListRow = toggleRow(binding!!.groupDeveloper, R.drawable.ic_description, R.string.full_logcat_logging_title, R.string.full_logcat_logging_description, AppPrefs.FULL_LOGCAT_LOGGING)
        fullLogcat.enabled(canLog)
        SegmentedGroup.apply(binding!!.groupDeveloper)
    }

    private fun buildOtherGroup() {
        ListRow.create(binding!!.groupOther).icon(R.drawable.ic_terminal).title(R.string.operation_mode).trailingText(session!!.getOperationMode().getLabel()).notClickable()
        ListRow.create(binding!!.groupOther).icon(R.drawable.ic_info).title(R.string.about).supporting(R.string.about_description).trailingIcon(R.drawable.ic_chevron_right).onClick({ v ->
            startActivity(Intent(this, AboutActivity::class.java))
        })
        SegmentedGroup.apply(binding!!.groupOther)
    }

    private fun toggleRow(group: android.widget.LinearLayout, @DrawableRes icon: Int, @StringRes title: Int, @StringRes supporting: Int, key: String): ListRow {
        val row: ListRow = ListRow.create(group).icon(icon).title(title)
        if ((supporting != 0)) {
            row.supporting(supporting)
        }
        row.toggle(appPrefs!!.getBoolean(key), Consumer { enabled ->
            appPrefs!!.setBoolean(key, enabled)
        })
        return row
    }

    private fun confirm(@DrawableRes icon: Int, @StringRes title: Int, @StringRes message: Int, onConfirm: Runnable, onRevert: Runnable) {
        MaterialAlertDialogBuilder(this).setIcon(icon).setTitle(title).setMessage(message).setPositiveButton(R.string.yes, { d, w ->
            onConfirm.run()
        }).setNegativeButton(R.string.no, { d, w ->
            onRevert.run()
        }).setOnCancelListener({ d ->
            onRevert.run()
        }).show()
    }

    protected override fun onDestroy() {
        super.onDestroy()
        binding = null
    }

    companion object {
        private const val STATE_COLORS_EXPANDED: String = "colors_expanded"

        /** Order shown in the theme dialog. */
        private val THEME_CHOICES: Array<ThemeMode> = arrayOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)
    }
}
