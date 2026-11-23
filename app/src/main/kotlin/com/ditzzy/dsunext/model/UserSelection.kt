package com.ditzzy.dsunext.model

import android.net.Uri
import com.ditzzy.dsunext.util.FilenameUtils

class UserSelection {
    @Volatile
    private var userSelectedUserdata: Long = DsuConstants.DEFAULT_USERDATA

    @Volatile
    private var userSelectedImageSize: Long = DsuConstants.DEFAULT_IMAGE_SIZE

    @Volatile
    private var selectedFileUri: Uri = Uri.EMPTY

    @Volatile
    private var selectedFileName: String = ""

    @Volatile
    private var gsiName: String = ""

    fun getUserSelectedUserdata(): Long {
        return userSelectedUserdata
    }

    fun getUserSelectedImageSize(): Long {
        return userSelectedImageSize
    }

    fun getSelectedFileUri(): Uri {
        return selectedFileUri
    }

    fun getSelectedFileName(): String {
        return selectedFileName
    }

    fun setSelectedFile(uri: Uri, name: String) {
        this.selectedFileUri = uri
        this.selectedFileName = name
    }

    fun getGsiName(): String {
        return gsiName
    }

    fun setGsiName(name: String?) {
        this.gsiName = (if ((name == null)) "" else name!!)
    }

    fun getUserDataSizeAsGb(): String {
        return java.lang.String.valueOf((userSelectedUserdata / GIGABYTE))
    }

    /** @param size the userdata size in gigabytes, empty means "use the default". */
    fun setUserDataSize(size: String) {
        val gigabytes: Long = parseDigits(size)
        userSelectedUserdata = (if ((gigabytes >= 0L)) (gigabytes * GIGABYTE) else DsuConstants.DEFAULT_USERDATA)
    }

    /** @param size the image size in bytes, empty means "let the app decide". */
    fun setImageSize(size: String) {
        val bytes: Long = parseDigits(size)
        userSelectedImageSize = (if ((bytes >= 0L)) bytes else DsuConstants.DEFAULT_IMAGE_SIZE)
    }

    fun isCustomImageSize(): Boolean {
        return (userSelectedImageSize != DsuConstants.DEFAULT_IMAGE_SIZE)
    }

    override fun toString(): String {
        return (((((((("UserSelection(userSelectedUserdata=" + userSelectedUserdata) + ", userSelectedImageSize=") + userSelectedImageSize) + ", selectedFileUri=") + selectedFileUri) + ", selectedFileName=") + selectedFileName) + ")")
    }

    companion object {
        private const val GIGABYTE: Long = ((1024L * 1024L) * 1024L)

        private fun parseDigits(input: String): Long {
            val digits: String = FilenameUtils.getDigits(input)
            if (digits.isEmpty()) {
                return -1L
            }
            try {
                return java.lang.Long.parseLong(digits)
            } catch (e: NumberFormatException) {
                return -1L
            }
        }
    }
}
