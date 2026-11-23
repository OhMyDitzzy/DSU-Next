package com.ditzzy.dsunext

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.ditzzy.dsunext.handler.CrashHandler
import com.ditzzy.dsunext.core.AppPrefs
import com.ditzzy.dsunext.core.LanguageManager
import com.ditzzy.dsunext.core.SlotRegistry
import com.ditzzy.dsunext.core.StorageManager
import com.ditzzy.dsunext.core.ThemeManager
import com.ditzzy.dsunext.model.Session
import com.topjohnwu.superuser.Shell
import org.lsposed.hiddenapibypass.HiddenApiBypass

open class DsuNextApp : Application() {
    private var appPrefs: AppPrefs? = null

    private var storageManager: StorageManager? = null

    private var themeManager: ThemeManager? = null

    private var languageManager: LanguageManager? = null

    private var session: Session? = null

    private var slotRegistry: SlotRegistry? = null

    protected override fun attachBaseContext(base: Context) {
        // Has to happen before anything touches a hidden API in this process
        HiddenApiBypass.addHiddenApiExemptions("")
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        if (getPackageName().equals(Application.getProcessName())) {
            CrashHandler.initialize(this)
        }
        // The default builder must be set before the first shell is requested
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR).setTimeout(10))
        appPrefs = AppPrefs(this)
        // Every process needs it (the crash screen included), so it is not guarded like the crash handler
        themeManager = ThemeManager(appPrefs!!)
        themeManager!!.install(this)
        // Same for the language, the crash screen has to be readable too
        languageManager = LanguageManager(this, appPrefs!!)
        languageManager!!.install()
        storageManager = StorageManager(this)
        session = Session()
        slotRegistry = SlotRegistry(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if ((languageManager != null)) {
            languageManager!!.onSystemConfigurationChanged()
        }
    }

    open fun getAppPrefs(): AppPrefs {
        return appPrefs!!
    }

    open fun getThemeManager(): ThemeManager {
        return themeManager!!
    }

    open fun getLanguageManager(): LanguageManager {
        return languageManager!!
    }

    open fun getStorageManager(): StorageManager {
        return storageManager!!
    }

    open fun getSession(): Session {
        return session!!
    }

    open fun getSlotRegistry(): SlotRegistry {
        return slotRegistry!!
    }

    companion object {
        @JvmStatic
        fun from(context: Context): DsuNextApp {
            return (context.getApplicationContext() as DsuNextApp)
        }
    }
}
