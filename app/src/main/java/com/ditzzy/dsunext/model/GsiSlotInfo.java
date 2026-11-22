package com.ditzzy.dsunext.model;

/** What DSU Next remembers about one installed GSI: gsid only knows the slot id. */
public final class GsiSlotInfo {

    private final String slot;
    private final String label;
    private final String sourceName;
    private final long installedAt;

    public GsiSlotInfo(String slot, String label, String sourceName, long installedAt) {
        this.slot = slot;
        this.label = label;
        this.sourceName = sourceName;
        this.installedAt = installedAt;
    }

    public String getSlot() {
        return slot;
    }

    /** The name the user sees. */
    public String getLabel() {
        return label;
    }

    /** The file the GSI was installed from, empty when unknown. */
    public String getSourceName() {
        return sourceName;
    }

    /** Epoch milliseconds, 0 when unknown. */
    public long getInstalledAt() {
        return installedAt;
    }

    public GsiSlotInfo withLabel(String newLabel) {
        return new GsiSlotInfo(slot, newLabel, sourceName, installedAt);
    }
}
