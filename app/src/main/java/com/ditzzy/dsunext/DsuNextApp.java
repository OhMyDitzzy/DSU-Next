package com.ditzzy.dsunext;

import android.app.Application;
import android.content.Context;

import com.ditzzy.dsunext.handler.CrashHandler;
import com.ditzzy.dsunext.core.AppPrefs;
import com.ditzzy.dsunext.core.StorageManager;
import com.ditzzy.dsunext.core.ThemeManager;
import com.ditzzy.dsunext.model.Session;
import com.topjohnwu.superuser.Shell;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class DsuNextApp extends Application {
    private AppPrefs appPrefs;
    private StorageManager storageManager;
    private ThemeManager themeManager;
    private Session session;
    
    @Override
    protected void attachBaseContext(Context base) {
        // Has to happen before anything touches a hidden API in this process
        HiddenApiBypass.addHiddenApiExemptions("");
        super.attachBaseContext(base);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        // Other processes (crash screen, root and Shizuku services) must not install the handler,
        // otherwise a failure in the crash screen would try to open the crash screen again
        if (getPackageName().equals(Application.getProcessName())) {
            CrashHandler.initialize(this);
        }
        // The default builder must be set before the first shell is requested
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10));

        appPrefs = new AppPrefs(this);
        // Every process needs it (the crash screen included), so it is not guarded like the crash handler
        themeManager = new ThemeManager(appPrefs);
        themeManager.install(this);
        storageManager = new StorageManager(this /*, appPrefs */);
        session = new Session();
    }
    
    public static DsuNextApp from(Context context) {
        return (DsuNextApp) context.getApplicationContext();
    }

    public AppPrefs getAppPrefs() {
        return appPrefs;
    }

    public ThemeManager getThemeManager() {
        return themeManager;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public Session getSession() {
        return session;
    }
}
