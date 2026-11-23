package com.ditzzy.dsunext.model

/** Values needed to build the adb installation command. */
class InstallationParameters {
    private val userdataSize: Long

    private val absoluteFilePath: String

    private val imageSize: Long

    constructor(userdataSize: Long, absoluteFilePath: String, imageSize: Long) {
        this.userdataSize = userdataSize
        this.absoluteFilePath = absoluteFilePath
        this.imageSize = imageSize
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
