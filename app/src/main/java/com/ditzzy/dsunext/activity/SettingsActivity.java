package com.ditzzy.dsunext.activity;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.transition.TransitionManager;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.ditzzy.dsunext.DsuNextApp;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.core.AppPrefs;
import com.ditzzy.dsunext.core.ThemeManager;
import com.ditzzy.dsunext.databinding.ActivitySettingsBinding;
import com.ditzzy.dsunext.model.OperationMode;
import com.ditzzy.dsunext.model.Session;
import com.ditzzy.dsunext.model.ThemeMode;
import com.ditzzy.dsunext.ui.ColorSwatchRow;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.ListRow;
import com.ditzzy.dsunext.ui.SegmentedGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SettingsActivity extends AppCompatActivity {

    private static final String STATE_COLORS_EXPANDED = "colors_expanded";

    /** Order shown in the theme dialog. */
    private static final ThemeMode[] THEME_CHOICES = {ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM};

    private ActivitySettingsBinding binding;
    private AppPrefs appPrefs;
    private Session session;
    private ThemeManager themeManager;

    private ListRow colorRow;
    private ColorSwatchRow swatchRow;
    private boolean colorsExpanded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DsuNextApp app = DsuNextApp.from(this);
        appPrefs = app.getAppPrefs();
        session = app.getSession();
        themeManager = app.getThemeManager();
        // Recreating the activity after a color change must not fold the swatches back
        colorsExpanded = savedInstanceState != null && savedInstanceState.getBoolean(STATE_COLORS_EXPANDED);

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.padBottomAndSides(binding.scrollView);

        buildAppearanceGroup();
        buildInstallationGroup();
        buildDeveloperGroup();
        buildOtherGroup();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Developer options can be toggled from the about screen
        int visibility = appPrefs.getBoolean(AppPrefs.DEVELOPER_OPTIONS) ? View.VISIBLE : View.GONE;
        binding.headerDeveloper.setVisibility(visibility);
        binding.groupDeveloper.setVisibility(visibility);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_COLORS_EXPANDED, colorsExpanded);
    }

    private void buildAppearanceGroup() {
        ListRow themeRow = ListRow.create(binding.groupAppearance)
                .icon(R.drawable.ic_brightness_medium)
                .title(R.string.theme)
                .trailingText(getString(themeManager.getThemeMode().getLabel()));
        themeRow.onClick(v -> showThemeDialog(themeRow));

        // Switch below is what disables the color swatches, so it comes first
        boolean dynamicSupported = ThemeManager.isDynamicColorSupported();
        ListRow dynamicRow = ListRow.create(binding.groupAppearance)
                .icon(R.drawable.ic_auto_awesome)
                .title(R.string.dynamic_color)
                .supporting(dynamicSupported
                        ? R.string.dynamic_color_description
                        : R.string.dynamic_color_unsupported)
                .enabled(dynamicSupported);
        dynamicRow.toggle(themeManager.isDynamicColorEnabled(), enabled -> {
            themeManager.setDynamicColorEnabled(enabled);
            updateColorState();
        });

        colorRow = ListRow.create(binding.groupAppearance)
                .icon(R.drawable.ic_palette)
                .title(R.string.color)
                .trailingIcon(R.drawable.ic_expand_more)
                .onClick(v -> setColorsExpanded(!colorsExpanded, true));
        swatchRow = ColorSwatchRow.create(binding.groupAppearance, themeManager.getColorPalette(), palette -> {
            swatchRow.select(palette);
            themeManager.setColorPalette(palette);
            updateColorState();
        });

        updateColorState();
        setColorsExpanded(colorsExpanded, false);
    }

    /** Swatches are dimmed and ignore taps while dynamic color is on. */
    private void updateColorState() {
        boolean dynamic = themeManager.isDynamicColorEnabled();
        swatchRow.enabled(!dynamic);
        colorRow.supporting(dynamic
                ? R.string.color_disabled_dynamic
                : themeManager.getColorPalette().getLabel());
    }

    private void setColorsExpanded(boolean expanded, boolean animate) {
        colorsExpanded = expanded;
        if (animate) {
            TransitionManager.beginDelayedTransition(binding.groupAppearance);
        }
        swatchRow.visible(expanded);
        // Corners of the segments depend on which rows are visible
        SegmentedGroup.apply(binding.groupAppearance);

        float rotation = expanded ? 180f : 0f;
        if (animate) {
            colorRow.trailingIconView().animate().rotation(rotation).setDuration(200).start();
        } else {
            colorRow.trailingIconView().setRotation(rotation);
        }
    }

    private void showThemeDialog(ListRow themeRow) {
        ThemeMode current = themeManager.getThemeMode();
        CharSequence[] labels = new CharSequence[THEME_CHOICES.length];
        int checked = 0;
        for (int i = 0; i < THEME_CHOICES.length; i++) {
            labels[i] = getString(THEME_CHOICES[i].getLabel());
            if (THEME_CHOICES[i] == current) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.theme)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    ThemeMode mode = THEME_CHOICES[which];
                    if (mode != themeManager.getThemeMode()) {
                        themeRow.trailingText(getString(mode.getLabel()));
                        themeManager.setThemeMode(mode);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void buildInstallationGroup() {
        boolean builtinSupported = Build.VERSION.SDK_INT != Build.VERSION_CODES.Q;
        boolean builtinAvailable = builtinSupported && session.isRoot();

        ListRow builtin = ListRow.create(binding.groupInstallation)
                .icon(R.drawable.ic_bolt)
                .title(R.string.builtin_installer)
                .supporting(builtinSupported
                        ? (session.isRoot() ? R.string.builtin_installer_description : R.string.requires_root)
                        : R.string.builtin_installer_unsupported)
                .enabled(builtinAvailable);
        boolean builtinOn = builtinAvailable && appPrefs.getBoolean(AppPrefs.USE_BUILTIN_INSTALLER);
        builtin.toggle(builtinOn, enabled -> {
            if (!enabled) {
                appPrefs.setBoolean(AppPrefs.USE_BUILTIN_INSTALLER, false);
                return;
            }
            confirm(R.drawable.ic_warning, R.string.experimental_feature, R.string.experimental_feature_description,
                    () -> appPrefs.setBoolean(AppPrefs.USE_BUILTIN_INSTALLER, true),
                    () -> builtin.checked(false));
        });

        toggleRow(binding.groupInstallation, R.drawable.ic_sd_card, R.string.unmount_sd_title,
                R.string.unmount_sd_description, AppPrefs.UMOUNT_SD);
        toggleRow(binding.groupInstallation, R.drawable.ic_phone, R.string.keep_screen_on, 0,
                AppPrefs.KEEP_SCREEN_ON);
        SegmentedGroup.apply(binding.groupInstallation);
    }

    private void buildDeveloperGroup() {
        ListRow storageCheck = ListRow.create(binding.groupDeveloper)
                .icon(R.drawable.ic_storage)
                .title(R.string.storage_check_title)
                .supporting(R.string.storage_check_description);
        storageCheck.toggle(appPrefs.getBoolean(AppPrefs.DISABLE_STORAGE_CHECK), disabled -> {
            if (!disabled) {
                appPrefs.setBoolean(AppPrefs.DISABLE_STORAGE_CHECK, false);
                return;
            }
            confirm(R.drawable.ic_warning, R.string.warning_storage_check_title,
                    R.string.warning_storage_check_description,
                    () -> appPrefs.setBoolean(AppPrefs.DISABLE_STORAGE_CHECK, true),
                    () -> storageCheck.checked(false));
        });

        // Without a privileged mode there is no installation to read the logs of
        boolean canLog = session.getOperationMode() != OperationMode.ADB;
        ListRow fullLogcat = toggleRow(binding.groupDeveloper, R.drawable.ic_description,
                R.string.full_logcat_logging_title, R.string.full_logcat_logging_description,
                AppPrefs.FULL_LOGCAT_LOGGING);
        fullLogcat.enabled(canLog);
        SegmentedGroup.apply(binding.groupDeveloper);
    }

    private void buildOtherGroup() {
        ListRow.create(binding.groupOther)
                .icon(R.drawable.ic_terminal)
                .title(R.string.operation_mode)
                .trailingText(session.getOperationMode().getLabel())
                .notClickable();

        ListRow.create(binding.groupOther)
                .icon(R.drawable.ic_info)
                .title(R.string.about)
                .supporting(R.string.about_description)
                .trailingIcon(R.drawable.ic_chevron_right)
                .onClick(v -> startActivity(new Intent(this, AboutActivity.class)));
        SegmentedGroup.apply(binding.groupOther);
    }

    private ListRow toggleRow(
            android.widget.LinearLayout group,
            @DrawableRes int icon,
            @StringRes int title,
            @StringRes int supporting,
            String key) {
        ListRow row = ListRow.create(group).icon(icon).title(title);
        if (supporting != 0) {
            row.supporting(supporting);
        }
        row.toggle(appPrefs.getBoolean(key), enabled -> appPrefs.setBoolean(key, enabled));
        return row;
    }

    private void confirm(
            @DrawableRes int icon,
            @StringRes int title,
            @StringRes int message,
            Runnable onConfirm,
            Runnable onRevert) {
        new MaterialAlertDialogBuilder(this)
                .setIcon(icon)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.yes, (d, w) -> onConfirm.run())
                .setNegativeButton(R.string.no, (d, w) -> onRevert.run())
                .setOnCancelListener(d -> onRevert.run())
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}