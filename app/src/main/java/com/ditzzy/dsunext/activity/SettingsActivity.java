package com.ditzzy.dsunext.activity;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.ditzzy.dsunext.DsuNextApp;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.core.AppPrefs;
import com.ditzzy.dsunext.databinding.ActivitySettingsBinding;
import com.ditzzy.dsunext.model.OperationMode;
import com.ditzzy.dsunext.model.Session;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.ListRow;
import com.ditzzy.dsunext.ui.SegmentedGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private AppPrefs appPrefs;
    private Session session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        DsuNextApp app = DsuNextApp.from(this);
        appPrefs = app.getAppPrefs();
        session = app.getSession();

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.padBottomAndSides(binding.scrollView);

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