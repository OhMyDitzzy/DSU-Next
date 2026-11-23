package com.ditzzy.dsunext.preparation

import android.net.Uri

/** A file produced (or validated) during preparation, along with the size DSU should be told. */
class PreparedFile {
    private val uri: Uri

    private val size: Long

    constructor(uri: Uri, size: Long) {
        this.uri = uri
        this.size = size
    }

    fun getUri(): Uri {
        return uri
    }

    fun getSize(): Long {
        return size
    }
}
