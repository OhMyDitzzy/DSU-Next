package com.ditzzy.dsunext.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ditzzy.dsunext.model.GsiSlotInfo
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList

/**
 * Remembers the name, source file and install time of every GSI installed through Multi-GSI.
 *
 * <p>gsid only keeps slot ids, so this is what turns "dsu_lfls" into "LineageOS 21". The data is
 * advisory: a slot that gsid has and the registry lacks is still listed (with its id as name),
 * and entries whose slot is gone are pruned on the next refresh.
 */
class SlotRegistry {
    private val preferences: SharedPreferences

    constructor(context: Context) {
        this.preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun read(): JSONObject {
        val raw: String? = preferences.getString(KEY_SLOTS, "")
        if (((raw == null) || raw!!.isEmpty())) {
            return JSONObject()
        }
        try {
            return JSONObject(raw)
        } catch (e: JSONException) {
            Log.w(TAG, "The stored slots are unreadable, starting over.", e)
            return JSONObject()
        }
    }

    @Synchronized
    private fun write(all: JSONObject) {
        preferences.edit().putString(KEY_SLOTS, all.toString()).apply()
    }

    @Synchronized
    fun `get`(slot: String): GsiSlotInfo? {
        val entry: JSONObject? = read().optJSONObject(slot)
        if ((entry == null)) {
            return null
        }
        return GsiSlotInfo(slot, entry!!.optString(F_LABEL, slot), entry!!.optString(F_SOURCE, ""), entry!!.optLong(F_TIME, 0L))
    }

    @Synchronized
    fun put(info: GsiSlotInfo) {
        val all: JSONObject = read()
        try {
            val entry: JSONObject = JSONObject()
            entry.put(F_LABEL, info.getLabel())
            entry.put(F_SOURCE, info.getSourceName())
            entry.put(F_TIME, info.getInstalledAt())
            all.put(info.getSlot(), entry)
            write(all)
        } catch (e: JSONException) {
            Log.e(TAG, ("Unable to store slot: " + info.getSlot()), e)
        }
    }

    @Synchronized
    fun remove(slot: String) {
        val all: JSONObject = read()
        if (all.has(slot)) {
            all.remove(slot)
            write(all)
        }
    }

    /**
     * The slot the user last chose to boot, empty when none. gsid's own "active slot" is not a
     * reliable answer for a one-shot switch, so the choice is remembered here and shown instead.
     */
    @Synchronized
    fun getSelected(): String {
        val selected: String? = preferences.getString(KEY_SELECTED, "")
        return (if ((selected == null)) "" else selected!!)
    }

    @Synchronized
    fun setSelected(slot: String?) {
        preferences.edit().putString(KEY_SELECTED, (if ((slot == null)) "" else slot!!)).apply()
    }

    /** Forgets every slot that gsid no longer has. */
    @Synchronized
    fun retainOnly(installedSlots: Collection<String>) {
        if ((!getSelected().isEmpty() && !installedSlots.contains(getSelected()))) {
            setSelected("")
        }
        val all: JSONObject = read()
        val stale: MutableList<String> = ArrayList<String>()
        val keys: MutableIterator<String> = all.keys()
        while (keys.hasNext()) {
            val key: String = keys.next()
            if (!installedSlots.contains(key)) {
                stale.add(key)
            }
        }
        if (stale.isEmpty()) {
            return
        }
        for (key in stale) {
            all.remove(key)
        }
        write(all)
    }

    companion object {
        private const val TAG: String = "SlotRegistry"

        private const val FILE: String = "multi_gsi_slots"

        private const val KEY_SLOTS: String = "slots"

        private const val KEY_SELECTED: String = "selected"

        private const val F_LABEL: String = "label"

        private const val F_SOURCE: String = "source"

        private const val F_TIME: String = "time"
    }
}
