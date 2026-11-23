package com.ditzzy.dsunext.model

import android.net.Uri
import java.util.Collections

/**
 * Describes what is going to be installed as a DSU. Instances are created through the
 * static factories so every [Type] always carries a consistent set of fields.
 */
class DsuInstallationSource {
    enum class Type {
        NONE,
        SINGLE_SYSTEM_IMAGE,
        DSU_PACKAGE,
        URL,
        MULTIPLE_IMAGES;

    }

    private val type: Type

    private val uri: Uri

    private val fileSize: Long

    private val images: List<ImagePartition>

    private constructor(type: Type, uri: Uri, fileSize: Long, images: List<ImagePartition>) {
        this.type = type
        this.uri = uri
        this.fileSize = fileSize
        this.images = images
    }

    fun getType(): Type {
        return type
    }

    fun getUri(): Uri {
        return uri
    }

    fun getFileSize(): Long {
        return fileSize
    }

    fun getImages(): List<ImagePartition> {
        return images
    }

    override fun toString(): String {
        return (((((("DsuInstallationSource(type=" + type) + ", uri=") + uri) + ", fileSize=") + fileSize) + ")")
    }

    companion object {
        @JvmStatic
        fun none(): DsuInstallationSource {
            return DsuInstallationSource(Type.NONE, Uri.EMPTY, DsuConstants.DEFAULT_IMAGE_SIZE, Collections.emptyList())
        }

        @JvmStatic
        fun singleSystemImage(uri: Uri, fileSize: Long): DsuInstallationSource {
            return DsuInstallationSource(Type.SINGLE_SYSTEM_IMAGE, uri, fileSize, Collections.emptyList())
        }

        @JvmStatic
        fun dsuPackage(uri: Uri): DsuInstallationSource {
            return DsuInstallationSource(Type.DSU_PACKAGE, uri, DsuConstants.DEFAULT_IMAGE_SIZE, Collections.emptyList())
        }

        @JvmStatic
        fun url(uri: Uri): DsuInstallationSource {
            return DsuInstallationSource(Type.URL, uri, DsuConstants.DEFAULT_IMAGE_SIZE, Collections.emptyList())
        }

        @JvmStatic
        fun multipleImages(images: List<ImagePartition>): DsuInstallationSource {
            return DsuInstallationSource(Type.MULTIPLE_IMAGES, Uri.EMPTY, DsuConstants.DEFAULT_IMAGE_SIZE, images)
        }
    }
}
