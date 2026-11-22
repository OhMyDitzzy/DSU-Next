package com.ditzzy.dsunext.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ditzzy.dsunext.DsuNextApp;
import com.ditzzy.dsunext.R;
import com.ditzzy.dsunext.core.AppPrefs;
import com.ditzzy.dsunext.core.SlotRegistry;
import com.ditzzy.dsunext.model.GsiSlotInfo;
import com.ditzzy.dsunext.model.Session;
import com.ditzzy.dsunext.service.PrivilegedProvider;
import com.ditzzy.dsunext.ui.Event;
import com.ditzzy.dsunext.ui.UiMessage;
import com.ditzzy.dsunext.util.GsiNames;
import com.ditzzy.dsunext.util.MultiGsi;
import com.topjohnwu.superuser.Shell;

import java.util.ArrayList;
import java.util.List;

/** Lists the installed GSIs and switches, renames or removes them. */
public final class MultiGsiViewModel extends AndroidViewModel {

    private static final String TAG = "MultiGsiViewModel";

    private final AppPrefs appPrefs;
    private final SlotRegistry registry;
    private final Session session;

    private final MutableLiveData<MultiGsiState> state =
            new MutableLiveData<>(MultiGsiState.of(MultiGsiState.Status.LOADING));
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<Event<UiMessage>> messages = new MutableLiveData<>();

    public MultiGsiViewModel(@NonNull Application application) {
        super(application);
        DsuNextApp app = DsuNextApp.from(application);
        appPrefs = app.getAppPrefs();
        registry = app.getSlotRegistry();
        session = app.getSession();
    }

    public LiveData<MultiGsiState> getState() {
        return state;
    }

    public LiveData<Boolean> getBusy() {
        return busy;
    }

    public LiveData<Event<UiMessage>> getMessages() {
        return messages;
    }

    /** Whether new installations currently go to their own slot. */
    public boolean isMultiGsiActive() {
        return MultiGsi.isActive(appPrefs, session);
    }

    private void postMessage(int resId, Object... args) {
        messages.postValue(new Event<>(new UiMessage(resId, args)));
    }

    public void refresh() {
        if (!MultiGsi.isSupported(session)) {
            state.postValue(MultiGsiState.of(MultiGsiState.Status.UNSUPPORTED));
            return;
        }
        PrivilegedProvider.run(service -> {
            if (!service.isSlotApiAvailable()) {
                state.postValue(MultiGsiState.of(MultiGsiState.Status.UNAVAILABLE));
                return;
            }
            boolean inUse = service.isInUse();
            List<String> slots = service.getInstalledSlots();
            String active = service.getActiveSlot();

            registry.retainOnly(slots);
            List<MultiGsiState.Item> items = new ArrayList<>();
            for (String slot : slots) {
                items.add(MultiGsiState.item(describe(slot), slot.equals(active)));
            }
            state.postValue(MultiGsiState.ready(inUse, items));
        }, () -> state.postValue(MultiGsiState.of(MultiGsiState.Status.UNAVAILABLE)));
    }

    // A slot that gsid has but the registry lacks (installed by another tool) is still listed
    private GsiSlotInfo describe(String slot) {
        GsiSlotInfo known = registry.get(slot);
        if (known != null) {
            return known;
        }
        String label = GsiNames.DEFAULT_SLOT.equals(slot)
                ? getApplication().getString(R.string.multi_gsi_default_label)
                : slot;
        return new GsiSlotInfo(slot, label, "", 0L);
    }

    /**
     * Makes {@code slot} the GSI of the next DSU boot.
     *
     * @param reboot reboot right away. Without it the choice is kept and the device boots the GSI
     *               the next time it is restarted.
     */
    public void switchTo(String slot, boolean reboot) {
        busy.postValue(true);
        PrivilegedProvider.run(service -> {
            // One shot: the GSI boots once, the reboot after that returns to the normal system
            if (!service.activateSlot(slot, true)) {
                postMessage(R.string.multi_gsi_switch_failed);
            } else if (reboot) {
                Shell.cmd("reboot").exec();
            } else {
                postMessage(R.string.multi_gsi_switch_done);
            }
            busy.postValue(false);
            refresh();
        }, () -> {
            busy.postValue(false);
            postMessage(R.string.multi_gsi_switch_failed);
        });
    }

    public void remove(String slot) {
        busy.postValue(true);
        PrivilegedProvider.run(service -> {
            if (service.removeSlot(slot)) {
                registry.remove(slot);
                postMessage(R.string.multi_gsi_removed);
            } else {
                postMessage(R.string.multi_gsi_remove_failed);
            }
            busy.postValue(false);
            refresh();
        }, () -> {
            busy.postValue(false);
            postMessage(R.string.multi_gsi_remove_failed);
        });
    }

    public void rename(String slot, String newLabel) {
        String label = GsiNames.sanitizeLabel(newLabel);
        if (label.isEmpty()) {
            return;
        }
        GsiSlotInfo existing = registry.get(slot);
        registry.put(existing != null ? existing.withLabel(label) : new GsiSlotInfo(slot, label, "", 0L));
        Log.d(TAG, "Renamed " + slot + " to " + label);
        refresh();
    }
}
