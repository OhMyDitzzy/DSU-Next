package com.ditzzy.dsunext.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.ditzzy.dsunext.DsuNextApp
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.databinding.ActivityAdbBinding
import com.ditzzy.dsunext.ui.InsetsUtils

open class AdbActivity : AppCompatActivity() {
    private var binding: ActivityAdbBinding? = null

    protected override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAdbBinding.inflate(getLayoutInflater())
        setContentView(binding!!.getRoot())
        binding!!.toolbar.setNavigationOnClickListener({ v ->
            finish()
        })
        InsetsUtils.padBottomAndSides(binding!!.scrollView)
        val scriptPath: String = DsuNextApp.from(this).getSession().getInstallationScriptPath()
        val shellCommand: String = (("sh \"" + scriptPath) + "\"")
        val adbCommand: String = ("adb shell " + shellCommand)
        binding!!.commandAdb.setText(adbCommand)
        binding!!.commandShell.setText(shellCommand)
        binding!!.copyAdb.setOnClickListener({ v ->
            copy(adbCommand)
        })
        binding!!.copyShell.setOnClickListener({ v ->
            copy(shellCommand)
        })
    }

    private fun copy(text: String) {
        val clipboard: ClipboardManager? = getSystemService(ClipboardManager::class.java)
        if ((clipboard == null)) {
            return
        }
        clipboard!!.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_text), text))
        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)) {
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    protected override fun onDestroy() {
        super.onDestroy()
        binding = null
    }
}
