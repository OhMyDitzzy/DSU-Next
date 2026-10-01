package com.ditzzy.dsunext.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.databinding.ActivityCrashBinding;
import com.ditzzy.dsunext.ui.InsetsUtils;

public class CrashActivity extends AppCompatActivity {

    private ActivityCrashBinding binding = null;
    private String crashLog = "";

    public static final String EXTRA_CRASH_INFO = "extra_crash_info";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        binding = ActivityCrashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String extra = getIntent().getStringExtra(EXTRA_CRASH_INFO);
        crashLog = (extra == null || extra.isEmpty()) ? getString(R.string.crash_no_log) : extra;
        binding.logText.setText(crashLog);

        binding.buttonCopy.setOnClickListener(v -> copyLog());
        binding.buttonRestart.setOnClickListener(v -> restartApp());

        InsetsUtils.padSides(binding.nestedScrollView);
        InsetsUtils.padBottomAndSides(binding.actionBar);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                closeApp();
            }
        });
    }

    private void copyLog() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_log_label), crashLog));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void restartApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
        Process.killProcess(Process.myPid());
    }

    private void closeApp() {
        finishAndRemoveTask();
        Process.killProcess(Process.myPid());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}