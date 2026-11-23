package com.ditzzy.dsunext.model

/** What DSU Next remembers about one installed GSI: gsid only knows the slot id. */
class GsiSlotInfo {
    private val slot: String

    private val label: String

    private val sourceName: String

    private val installedAt: Long

    constructor(slot: String, label: String, sourceName: String, installedAt: Long) {
        this.slot = slot
        this.label = label
        this.sourceName = sourceName
        this.installedAt = installedAt
    }

    fun getSlot(): String {
        return slot
    }

    /** The name the user sees. */
    fun getLabel(): String {
        return label
    }

    /** The file the GSI was installed from, empty when unknown. */
    fun getSourceName(): String {
        return sourceName
    }

    /** Epoch milliseconds, 0 when unknown. */
    fun getInstalledAt(): Long {
        return installedAt
    }

    fun withLabel(newLabel: String): GsiSlotInfo {
        return GsiSlotInfo(slot, newLabel, sourceName, installedAt)
    }
}
