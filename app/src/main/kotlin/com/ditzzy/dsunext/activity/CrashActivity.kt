package com.ditzzy.dsunext.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.ActivityCrashBinding
import com.ditzzy.dsunext.ui.InsetsUtils

open class CrashActivity : AppCompatActivity() {
    private var binding: ActivityCrashBinding? = null

    private var crashLog: String = ""

    protected override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        val extra: String? = getIntent().getStringExtra(EXTRA_CRASH_INFO)
        crashLog = (if (((extra == null) || extra!!.isEmpty())) getString(R.string.crash_no_log) else extra!!)
        binding!!.logText.setText(crashLog)
        binding!!.buttonCopy.setOnClickListener({ v ->
            copyLog()
        })
        binding!!.buttonRestart.setOnClickListener({ v ->
            restartApp()
        })
        InsetsUtils.padSides(binding!!.nestedScrollView)
        InsetsUtils.padBottomAndSides(binding!!.actionBar)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeApp()
            }
        })
    }

    private fun copyLog() {
        val clipboard: ClipboardManager? = getSystemService(ClipboardManager::class.java)
        if ((clipboard == null)) {
            return
        }
        clipboard!!.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_log_label), crashLog))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun restartApp() {
        val intent: Intent = Intent(this, MainActivity::class.java)
        intent.addFlags((Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        startActivity(intent)
        finish()
        Process.killProcess(Process.myPid())
    }

    private fun closeApp() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    protected override fun onDestroy() {
        super.onDestroy()
        binding = null
    }

    companion object {
        const val EXTRA_CRASH_INFO: String = "extra_crash_info"
    }
}
