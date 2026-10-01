package com.ditzzy.dsunext.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.ditzzy.dsunext.DsuNextApp;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityAdbBinding;
import com.ditzzy.dsunext.ui.InsetsUtils;

public class AdbActivity extends AppCompatActivity {

    private ActivityAdbBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityAdbBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.padBottomAndSides(binding.scrollView);

        String scriptPath = DsuNextApp.from(this).getSession().getInstallationScriptPath();
        String shellCommand = "sh \"" + scriptPath + "\"";
        String adbCommand = "adb shell " + shellCommand;

        binding.commandAdb.setText(adbCommand);
        binding.commandShell.setText(shellCommand);
        binding.copyAdb.setOnClickListener(v -> copy(adbCommand));
        binding.copyShell.setOnClickListener(v -> copy(shellCommand));
    }

    private void copy(String text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_text), text));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}