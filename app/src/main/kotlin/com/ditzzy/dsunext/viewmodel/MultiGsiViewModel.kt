package com.ditzzy.dsunext.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ditzzy.dsunext.DsuNextApp
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.core.AppPrefs
import com.ditzzy.dsunext.core.SlotRegistry
import com.ditzzy.dsunext.model.GsiSlotInfo
import com.ditzzy.dsunext.model.Session
import com.ditzzy.dsunext.service.PrivilegedProvider
import com.ditzzy.dsunext.service.PrivilegedProvider.ServiceAction
import com.ditzzy.dsunext.ui.Event
import com.ditzzy.dsunext.ui.UiMessage
import com.ditzzy.dsunext.util.GsiNames
import com.ditzzy.dsunext.util.MultiGsi
import com.topjohnwu.superuser.Shell
import java.util.ArrayList

/** Lists the installed GSIs and switches, renames or removes them. */
class MultiGsiViewModel : AndroidViewModel {
    private val appPrefs: AppPrefs

    private val registry: SlotRegistry

    private val session: Session

    private val state: MutableLiveData<MultiGsiState> = MutableLiveData<MultiGsiState>(MultiGsiState.of(MultiGsiState.Status.LOADING))

    private val busy: MutableLiveData<Boolean> = MutableLiveData<Boolean>(false)

    private val messages: MutableLiveData<Event<UiMessage>> = MutableLiveData<Event<UiMessage>>()

    constructor(application: Application) : super(application) {

        val app: DsuNextApp = DsuNextApp.from(application)
        appPrefs = app.getAppPrefs()
        registry = app.getSlotRegistry()
        session = app.getSession()
    }

    fun getState(): LiveData<MultiGsiState> {
        return state
    }

    fun getBusy(): LiveData<Boolean> {
        return busy
    }

    fun getMessages(): LiveData<Event<UiMessage>> {
        return messages
    }

    /** Whether new installations currently go to their own slot. */
    fun isMultiGsiActive(): Boolean {
        return MultiGsi.isActive(appPrefs, session)
    }

    private fun postMessage(resId: Int, vararg args: Any?) {
        messages.postValue(Event<UiMessage>(UiMessage(resId, *args)))
    }

    fun refresh() {
        if (!MultiGsi.isSupported(session)) {
            state.postValue(MultiGsiState.of(MultiGsiState.Status.UNSUPPORTED))
            return
        }
        PrivilegedProvider.run(ServiceAction callback3@ { service ->
            try {
                if (!service.isSlotApiAvailable()) {
                    state.postValue(MultiGsiState.unavailable(service.getSlotApiStatus()))
                    return@callback3
                }
                val inUse: Boolean = service.isInUse()
                val slots: List<String> = service.getInstalledSlots()
                val gsidActive: String = service.getActiveSlot()
                registry.retainOnly(slots)
                val selected: String = registry.getSelected()
                val active: String = (if ((!selected.isEmpty() && slots.contains(selected))) selected else gsidActive)
                val items: MutableList<MultiGsiState.Item> = ArrayList<MultiGsiState.Item>()
                for (slot in slots) {
                    items.add(MultiGsiState.item(describe(slot), slot.equals(active)))
                }
                state.postValue(MultiGsiState.ready(inUse, items))
            } catch (e: Exception) {
                Log.e(TAG, "Unable to read the installed GSIs.", e)
                state.postValue(MultiGsiState.unavailable(e.toString()))
            }
        }, Runnable {
            state.postValue(MultiGsiState.unavailable("The privileged service did not connect."))
        })
    }

    // A slot that gsid has but the registry lacks (installed by another tool) is still listed
    private fun describe(slot: String): GsiSlotInfo {
        val known: GsiSlotInfo? = registry.`get`(slot)
        if ((known != null)) {
            return known!!
        }
        val label: String = (if (GsiNames.DEFAULT_SLOT.equals(slot)) getApplication<Application>().getString(R.string.multi_gsi_default_label) else slot)
        return GsiSlotInfo(slot, label, "", 0L)
    }

    /**
     * Makes `slot` the GSI of the next DSU boot.
     *
     * @param reboot reboot right away. Without it the choice is kept and the device boots the GSI
     *               the next time it is restarted.
     */
    fun switchTo(slot: String, reboot: Boolean) {
        busy.postValue(true)
        PrivilegedProvider.run(ServiceAction { service ->
            if (!service.activateSlot(slot, true)) {
                postMessage(R.string.multi_gsi_switch_failed)
            } else if (reboot) {
                registry.setSelected(slot)
                Shell.cmd("reboot").exec()
            } else {
                registry.setSelected(slot)
                postMessage(R.string.multi_gsi_switch_done)
            }
            busy.postValue(false)
            refresh()
        }, Runnable {
            busy.postValue(false)
            postMessage(R.string.multi_gsi_switch_failed)
        })
    }

    fun remove(slot: String) {
        busy.postValue(true)
        PrivilegedProvider.run(ServiceAction { service ->
            if (service.removeSlot(slot)) {
                registry.remove(slot)
                if (slot.equals(registry.getSelected())) {
                    registry.setSelected("")
                }
                postMessage(R.string.multi_gsi_removed)
            } else {
                postMessage(R.string.multi_gsi_remove_failed)
            }
            busy.postValue(false)
            refresh()
        }, Runnable {
            busy.postValue(false)
            postMessage(R.string.multi_gsi_remove_failed)
        })
    }

    fun rename(slot: String, newLabel: String) {
        val label: String = GsiNames.sanitizeLabel(newLabel)
        if (label.isEmpty()) {
            return
        }
        val existing: GsiSlotInfo? = registry.`get`(slot)
        registry.put((if ((existing != null)) existing!!.withLabel(label) else GsiSlotInfo(slot, label, "", 0L)))
        Log.d(TAG, ((("Renamed " + slot) + " to ") + label))
        refresh()
    }

    companion object {
        private const val TAG: String = "MultiGsiViewModel"
    }
}
