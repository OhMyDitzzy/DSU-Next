package com.ditzzy.dsunext.model

class InstallationPreferences {
    @Volatile
    private var unmountSdCard: Boolean = false

    @Volatile
    private var useBuiltinInstaller: Boolean = false

    fun isUnmountSdCard(): Boolean {
        return unmountSdCard
    }

    fun setUnmountSdCard(unmountSdCard: Boolean) {
        this.unmountSdCard = unmountSdCard
    }

    fun isUseBuiltinInstaller(): Boolean {
        return useBuiltinInstaller
    }

    fun setUseBuiltinInstaller(useBuiltinInstaller: Boolean) {
        this.useBuiltinInstaller = useBuiltinInstaller
    }

    override fun toString(): String {
        return (((("InstallationPreferences(unmountSdCard=" + unmountSdCard) + ", useBuiltinInstaller=") + useBuiltinInstaller) + ")")
    }
}
