package com.ditzzy.dsunext.model

import android.net.Uri

class ImagePartition {
    private val partitionName: String

    private val uri: Uri

    private val fileSize: Long

    constructor(partitionName: String, uri: Uri, fileSize: Long) {
        this.partitionName = partitionName
        this.uri = uri
        this.fileSize = fileSize
    }

    fun getPartitionName(): String {
        return partitionName
    }

    fun getUri(): Uri {
        return uri
    }

    fun getFileSize(): Long {
        return fileSize
    }
}
