package com.ditzzy.dsunext.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ditzzy.dsunext.model.GsiSlotInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Remembers the name, source file and install time of every GSI installed through Multi-GSI.
 *
 * <p>gsid only keeps slot ids, so this is what turns "dsu_lfls" into "LineageOS 21". The data is
 * advisory: a slot that gsid has and the registry lacks is still listed (with its id as name),
 * and entries whose slot is gone are pruned on the next refresh.
 */
public final class SlotRegistry {

    private static final String TAG = "SlotRegistry";
    private static final String FILE = "multi_gsi_slots";
    private static final String KEY_SLOTS = "slots";
    private static final String KEY_SELECTED = "selected";

    private static final String F_LABEL = "label";
    private static final String F_SOURCE = "source";
    private static final String F_TIME = "time";

    private final SharedPreferences preferences;

    public SlotRegistry(Context context) {
        this.preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private synchronized JSONObject read() {
        String raw = preferences.getString(KEY_SLOTS, "");
        if (raw == null || raw.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            Log.w(TAG, "The stored slots are unreadable, starting over.", e);
            return new JSONObject();
        }
    }

    private synchronized void write(JSONObject all) {
        preferences.edit().putString(KEY_SLOTS, all.toString()).apply();
    }

    @Nullable
    public synchronized GsiSlotInfo get(String slot) {
        JSONObject entry = read().optJSONObject(slot);
        if (entry == null) {
            return null;
        }
        return new GsiSlotInfo(
                slot,
                entry.optString(F_LABEL, slot),
                entry.optString(F_SOURCE, ""),
                entry.optLong(F_TIME, 0L));
    }

    public synchronized void put(GsiSlotInfo info) {
        JSONObject all = read();
        try {
            JSONObject entry = new JSONObject();
            entry.put(F_LABEL, info.getLabel());
            entry.put(F_SOURCE, info.getSourceName());
            entry.put(F_TIME, info.getInstalledAt());
            all.put(info.getSlot(), entry);
            write(all);
        } catch (JSONException e) {
            Log.e(TAG, "Unable to store slot: " + info.getSlot(), e);
        }
    }

    public synchronized void remove(String slot) {
        JSONObject all = read();
        if (all.has(slot)) {
            all.remove(slot);
            write(all);
        }
    }

    /**
     * The slot the user last chose to boot, empty when none. gsid's own "active slot" is not a
     * reliable answer for a one-shot switch, so the choice is remembered here and shown instead.
     */
    public synchronized String getSelected() {
        String selected = preferences.getString(KEY_SELECTED, "");
        return selected == null ? "" : selected;
    }

    public synchronized void setSelected(String slot) {
        preferences.edit().putString(KEY_SELECTED, slot == null ? "" : slot).apply();
    }

    /** Forgets every slot that gsid no longer has. */
    public synchronized void retainOnly(Collection<String> installedSlots) {
        if (!getSelected().isEmpty() && !installedSlots.contains(getSelected())) {
            setSelected("");
        }
        JSONObject all = read();
        List<String> stale = new ArrayList<>();
        Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!installedSlots.contains(key)) {
                stale.add(key);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        for (String key : stale) {
            all.remove(key);
        }
        write(all);
    }
}
