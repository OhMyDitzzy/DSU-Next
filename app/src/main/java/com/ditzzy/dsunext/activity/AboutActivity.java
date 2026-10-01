package com.ditzzy.dsunext.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.ditzzy.dsunext.BuildConfig;
import com.ditzzy.dsunext.DsuNextApp;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.core.AppPrefs;
import com.ditzzy.dsunext.databinding.ActivityAboutBinding;
import com.ditzzy.dsunext.ui.InsetsUtils;
import com.ditzzy.dsunext.ui.ListRow;
import com.ditzzy.dsunext.ui.SegmentedGroup;

public class AboutActivity extends AppCompatActivity {

    private static final String TAG = "AboutActivity";
    private static final String DEVELOPER_URL = "https://github.com/OhMyDitzzy";
    private static final String BASE_PROJECT_URL = "https://github.com/VegaBobo/DSU-Sideloader";
    private static final int CLICKS_FOR_DEVELOPER_OPTIONS = 8;

    private ActivityAboutBinding binding;
    private AppPrefs appPrefs;
    private int iconClicks = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        appPrefs = DsuNextApp.from(this).getAppPrefs();

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.padBottomAndSides(binding.scrollView);

        binding.aboutVersion.setText(getString(R.string.version_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        binding.aboutIcon.setOnClickListener(v -> onIconClicked());

        ListRow.create(binding.groupApplication)
                .icon(R.drawable.ic_code)
                .title(R.string.developer_github)
                .supporting(R.string.developer_github_description)
                .trailingIcon(R.drawable.ic_open_in_new)
                .onClick(v -> openUrl(DEVELOPER_URL));
        ListRow.create(binding.groupApplication)
                .icon(R.drawable.ic_description)
                .title(R.string.user_agreement)
                .supporting(R.string.user_agreement_description)
                .trailingIcon(R.drawable.ic_chevron_right)
                .onClick(v -> startActivity(AgreementActivity.createReadOnlyIntent(this)));
        SegmentedGroup.apply(binding.groupApplication);

        ListRow.create(binding.groupCredits)
                .icon(R.drawable.ic_code)
                .title(R.string.credit_base_project)
                .supporting(R.string.credit_base_project_description)
                .trailingIcon(R.drawable.ic_open_in_new)
                .onClick(v -> openUrl(BASE_PROJECT_URL));
        SegmentedGroup.apply(binding.groupCredits);
    }

    private void onIconClicked() {
        iconClicks++;
        if (iconClicks < CLICKS_FOR_DEVELOPER_OPTIONS) {
            return;
        }
        iconClicks = 0;
        boolean enabled = !appPrefs.getBoolean(AppPrefs.DEVELOPER_OPTIONS);
        appPrefs.setBoolean(AppPrefs.DEVELOPER_OPTIONS, enabled);
        Toast.makeText(this,
                enabled ? R.string.developer_options_enabled : R.string.developer_options_disabled,
                Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Log.w(TAG, "No app can open " + url, e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}