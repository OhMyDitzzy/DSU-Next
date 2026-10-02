package com.ditzzy.dsunext.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.ditzzy.dsunext.checker.treble.TrebleChecker;
import com.ditzzy.dsunext.checker.treble.TrebleReport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs the Treble check off the main thread and keeps the result across configuration changes. */
public final class TrebleViewModel extends ViewModel {

    /** The check is quick, this keeps the loading indicator from just flashing on screen. */
    private static final long MIN_LOADING_MS = 600L;

    private final MutableLiveData<TrebleReport> report = new MutableLiveData<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean started;

    public LiveData<TrebleReport> getReport() {
        return report;
    }

    /** Starts the check once, calling it again (rotation, re-entering the screen) does nothing. */
    public void load() {
        if (started) {
            return;
        }
        started = true;
        io.execute(() -> {
            long start = System.currentTimeMillis();
            TrebleReport result = TrebleChecker.check();
            long remaining = MIN_LOADING_MS - (System.currentTimeMillis() - start);
            if (remaining > 0) {
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            report.postValue(result);
        });
    }

    @Override
    protected void onCleared() {
        io.shutdownNow();
    }
}
