/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ditzzy.dsunext.installer.root

import java.lang.Math.min
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Locale

/**
 * SparseInputStream read from upstream and detects the data format. If the upstream is a valid
 * sparse data, it will unsparse it on the fly. Otherwise, it just passthrough as is.
 */
open class SparseInputStream : InputStream {
    /**
     * This class represents a chunk in the Android sparse image.
     *
     * \@see system/core/libsparse/sparse_format.h
     */
    private open class SparseChunk {
        @JvmField
        var mChunkType: Short = 0

        @JvmField
        var mChunkSize: Int = 0

        @JvmField
        var mTotalSize: Int = 0

        lateinit var fill: ByteArray

        override fun toString(): String {
            return java.lang.String.format(Locale.getDefault(), "type: %x, chunk_size: %d, total_size: %d", mChunkType, mChunkSize, mTotalSize)
        }

        companion object {
            val RAW: Short = (0xCAC1).toShort()

            val FILL: Short = (0xCAC2).toShort()

            val DONTCARE: Short = (0xCAC3).toShort()
        }
    }

    @Throws(IOException::class)
    private fun readFull(`in`: InputStream, size: Int): ByteArray {
        val buf: ByteArray = ByteArray(size)
        run {
            var done: Int = 0
            var n: Int = 0
            while ((done < size)) {
                if (((`in`.read(buf, done, (size - done))).also { n = it } < 0)) {
                    throw IOException("Failed to readFull")
                }
                (n).also { done += it }
            }
        }
        return buf
    }

    @Throws(IOException::class)
    private fun readBuffer(`in`: InputStream, size: Int): ByteBuffer {
        return ByteBuffer.wrap(readFull(`in`, size)).order(ByteOrder.LITTLE_ENDIAN)
    }

    @Throws(IOException::class)
    private fun readChunk(`in`: InputStream): SparseChunk {
        val chunk: SparseChunk = SparseChunk()
        val buf: ByteBuffer = readBuffer(`in`, CHUNK_HDR_SIZE)
        chunk.mChunkType = buf.getShort()
        buf.getShort()
        chunk.mChunkSize = buf.getInt()
        chunk.mTotalSize = buf.getInt()
        return chunk
    }

    private var mIn: BufferedInputStream? = null

    private var mIsSparse: Boolean = false

    private var mBlockSize: Long = 0L

    private var mTotalBlocks: Long = 0L

    private var mTotalChunks: Long = 0L

    private var mCur: SparseChunk? = null

    private var mLeft: Long = 0L

    private var mCurChunks: Int = 0

    @Throws(IOException::class)
    constructor(`in`: BufferedInputStream) : super() {
        mIn = `in`
        `in`.mark((FILE_HDR_SIZE * 2))
        val buf: ByteBuffer = readBuffer(mIn!!, FILE_HDR_SIZE)
        mIsSparse = (buf.getInt() == 0xed26ff3a.toInt())
        if (!mIsSparse) {
            mIn!!.reset()
            return
        }
        val major: Int = (buf.getShort()).toInt()
        val minor: Int = (buf.getShort()).toInt()
        if (((major > 0x1) || (minor > 0x0))) {
            throw IOException(((("Unsupported sparse version: " + major) + ".") + minor))
        }
        if (((buf.getShort()).toInt() != FILE_HDR_SIZE)) {
            throw IOException("Illegal file header size")
        }
        if (((buf.getShort()).toInt() != CHUNK_HDR_SIZE)) {
            throw IOException("Illegal chunk header size")
        }
        mBlockSize = (buf.getInt()).toLong()
        if (((mBlockSize and (0x3).toLong()) != 0L)) {
            throw IOException("Illegal block size, must be a multiple of 4")
        }
        mTotalBlocks = (buf.getInt()).toLong()
        mTotalChunks = (buf.getInt()).toLong()
        mLeft = ((0).also { mCurChunks = it }).toLong()
    }

    /**
     * Check if it needs to open a new chunk.
     *
     * @return true if it's EOF
     */
    @Throws(IOException::class)
    private fun prepareChunk(): Boolean {
        if (((mCur == null) || (mLeft <= 0L))) {
            if (((++mCurChunks).toLong() > mTotalChunks)) {
                return true
            }
            mCur = readChunk(mIn!!)
            if ((mCur!!.mChunkType == SparseChunk.FILL)) {
                mCur!!.fill = readFull(mIn!!, 4)
            }
            mLeft = ((mCur!!.mChunkSize).toLong() * mBlockSize)
        }
        return (mLeft == 0L)
    }

    @Throws(IOException::class)
    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (!mIsSparse) {
            return mIn!!.read(buf, off, len)
        }
        if (prepareChunk()) {
            return -1
        }
        var n: Int = -1
        when (mCur!!.mChunkType) {
            SparseChunk.RAW -> {
                n = mIn!!.read(buf, off, (min(mLeft, (len).toLong())).toInt())
                mLeft -= (n).toLong()
                return n
            }
            SparseChunk.DONTCARE -> {
                n = (min(mLeft, (len).toLong())).toInt()
                Arrays.fill(buf, off, (off + n), (0).toByte())
                mLeft -= (n).toLong()
                return n
            }
            SparseChunk.FILL -> {
                return super.read(buf, off, len)
            }
            else -> {
                throw IOException(("Unsupported Chunk:" + mCur!!.toString()))
            }
        }
    }

    @Throws(IOException::class)
    override fun read(): Int {
        if (!mIsSparse) {
            return mIn!!.read()
        }
        if (prepareChunk()) {
            return -1
        }
        var ret: Int = -1
        when (mCur!!.mChunkType) {
            SparseChunk.RAW -> {
                ret = mIn!!.read()
            }
            SparseChunk.DONTCARE -> {
                ret = 0
            }
            SparseChunk.FILL -> {
                ret = java.lang.Byte.toUnsignedInt(mCur!!.fill[((4 - ((mLeft).toInt() and 0x3)) and 0x3)])
            }
            else -> {
                throw IOException(("Unsupported Chunk:" + mCur!!.toString()))
            }
        }
        mLeft--
        return ret
    }

    /**
     * Get the unsparse size
     * @return -1 if unknown
     */
    open fun getUnsparseSize(): Long {
        if (!mIsSparse) {
            return -1L
        }
        return (mBlockSize * mTotalBlocks)
    }

    companion object {
        const val FILE_HDR_SIZE: Int = 28

        const val CHUNK_HDR_SIZE: Int = 12
    }
}
