package com.ditzzy.dsunext.model

/** Values needed to build the adb installation command. */
class InstallationParameters {
    private val userdataSize: Long

    private val absoluteFilePath: String

    private val imageSize: Long

    /** The DSU slot to install into, empty for the DSU app's default. */
    private val dsuSlot: String

    constructor(userdataSize: Long, absoluteFilePath: String, imageSize: Long) : this(userdataSize, absoluteFilePath, imageSize, "") {

    }

    constructor(userdataSize: Long, absoluteFilePath: String, imageSize: Long, dsuSlot: String) {
        this.userdataSize = userdataSize
        this.absoluteFilePath = absoluteFilePath
        this.imageSize = imageSize
        this.dsuSlot = dsuSlot
    }

    fun getDsuSlot(): String {
        return dsuSlot
    }

    fun getUserdataSize(): Long {
        return userdataSize
    }

    fun getAbsoluteFilePath(): String {
        return absoluteFilePath
    }

    fun getImageSize(): Long {
        return imageSize
    }
}
