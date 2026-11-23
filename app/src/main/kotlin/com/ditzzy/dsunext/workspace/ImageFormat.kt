package com.ditzzy.dsunext.workspace

import java.io.IOException

class ImageFormat {
    enum class Container {
        EXT4,
        EROFS,
        SPARSE,
        ZIP,
        SEVEN_Z,
        GZIP,
        XZ,
        TAR,
        UNKNOWN;

        fun isRawImage(): Boolean {
            return ((this == EXT4) || (this == EROFS))
        }
    }

    private constructor() {

    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun detect(source: ImageSource): Container {
            val head: ByteArray = source.readAt(0L, 8)
            if (startsWith(head, 0x3A, 0xFF, 0x26, 0xED)) {
                return Container.SPARSE
            }
            if (startsWith(head, ('P').code, ('K').code, 0x03, 0x04)) {
                return Container.ZIP
            }
            if (startsWith(head, (0x1f).toInt(), 0x8B)) {
                return Container.GZIP
            }
            if (startsWith(head, 0xFD, ('7').code, ('z').code, ('X').code, ('Z').code, 0x00)) {
                return Container.XZ
            }
            if (startsWith(head, ('7').code, ('z').code, 0xBC, (0xAf).toInt(), 0x27, 0x1C)) {
                return Container.SEVEN_Z
            }
            // EROFS magic 0xE0F5E1E2 at 1024, ext4 magic 0xEF53 at 1080, both little endian
            if (startsWith(source.readAt(1024L, 4), 0xE2, 0xE1, 0xF5, 0xE0)) {
                return Container.EROFS
            }
            if (startsWith(source.readAt(1080L, 2), 0x53, (0xEf).toInt())) {
                return Container.EXT4
            }
            if (isTar(source.readAt(257L, 5))) {
                return Container.TAR
            }
            return Container.UNKNOWN
        }

        /** True when the first 512 bytes of a stream are a tar header. */
        @JvmStatic
        fun isTarHeader(head: ByteArray, length: Int): Boolean {
            if ((length < 262)) {
                return false
            }
            val magic: ByteArray = ByteArray(5)
            System.arraycopy(head, 257, magic, 0, 5)
            return isTar(magic)
        }

        private fun isTar(magic: ByteArray): Boolean {
            return startsWith(magic, ('u').code, ('s').code, ('t').code, ('a').code, ('r').code)
        }

        private fun startsWith(`data`: ByteArray, vararg expected: Int): Boolean {
            if ((`data`.size < expected.size)) {
                return false
            }
            run {
                var i: Int = 0
                while ((i < expected.size)) {
                    if ((((`data`[i]).toInt() and (0xFf).toInt()) != expected[i])) {
                        return false
                    }
                    i++
                }
            }
            return true
        }
    }
}
