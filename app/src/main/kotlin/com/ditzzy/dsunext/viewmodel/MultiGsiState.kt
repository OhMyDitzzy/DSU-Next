package com.ditzzy.dsunext.viewmodel

import com.ditzzy.dsunext.model.GsiSlotInfo
import java.util.Collections

/** Everything the Multi-GSI screen shows. Instances are never changed after they are posted. */
class MultiGsiState {
    enum class Status {
        LOADING,
        READY,
        /** Needs root and Android 11 or newer. */
        UNSUPPORTED,
        /** Root is there, but gsid or the privileged service did not answer. */
        UNAVAILABLE;

    }

    class Item {
        val info: GsiSlotInfo

        /** The slot gsid boots when DSU is enabled. */
        val active: Boolean

        constructor(info: GsiSlotInfo, active: Boolean) {
            this.info = info
            this.active = active
        }
    }

    val status: Status

    /** A DSU is running right now, so slots cannot be switched or removed. */
    val inUse: Boolean

    val items: List<Item>

    /** Why the screen is UNAVAILABLE, empty otherwise. */
    val detail: String?

    private constructor(status: Status, inUse: Boolean, items: List<Item>, detail: String) {
        this.status = status
        this.inUse = inUse
        this.items = items
        this.detail = detail
    }

    companion object {
        @JvmStatic
        fun of(status: Status): MultiGsiState {
            return MultiGsiState(status, false, Collections.emptyList(), "")
        }

        @JvmStatic
        fun unavailable(detail: String?): MultiGsiState {
            return MultiGsiState(Status.UNAVAILABLE, false, Collections.emptyList(), (if ((detail == null)) "" else detail!!))
        }

        @JvmStatic
        fun ready(inUse: Boolean, items: List<Item>): MultiGsiState {
            return MultiGsiState(Status.READY, inUse, items, "")
        }

        @JvmStatic
        fun item(info: GsiSlotInfo, active: Boolean): Item {
            return Item(info, active)
        }
    }
}
