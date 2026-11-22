package com.ditzzy.dsunext.viewmodel;

import com.ditzzy.dsunext.model.GsiSlotInfo;

import java.util.Collections;
import java.util.List;

/** Everything the Multi-GSI screen shows. Instances are never changed after they are posted. */
public final class MultiGsiState {

    public enum Status {
        LOADING,
        READY,
        /** Needs root and Android 11 or newer. */
        UNSUPPORTED,
        /** Root is there, but gsid or the privileged service did not answer. */
        UNAVAILABLE
    }

    public static final class Item {
        public final GsiSlotInfo info;
        /** The slot gsid boots when DSU is enabled. */
        public final boolean active;

        Item(GsiSlotInfo info, boolean active) {
            this.info = info;
            this.active = active;
        }
    }

    public final Status status;
    /** A DSU is running right now, so slots cannot be switched or removed. */
    public final boolean inUse;
    public final List<Item> items;
    /** Why the screen is UNAVAILABLE, empty otherwise. */
    public final String detail;

    private MultiGsiState(Status status, boolean inUse, List<Item> items, String detail) {
        this.status = status;
        this.inUse = inUse;
        this.items = items;
        this.detail = detail;
    }

    public static MultiGsiState of(Status status) {
        return new MultiGsiState(status, false, Collections.emptyList(), "");
    }

    public static MultiGsiState unavailable(String detail) {
        return new MultiGsiState(Status.UNAVAILABLE, false, Collections.emptyList(), detail == null ? "" : detail);
    }

    public static MultiGsiState ready(boolean inUse, List<Item> items) {
        return new MultiGsiState(Status.READY, inUse, items, "");
    }

    static Item item(GsiSlotInfo info, boolean active) {
        return new Item(info, active);
    }
}
