package com.ditzzy.dsunext.workspace

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Arrays

class ImageSource : Closeable {
    private val name: String

    private val localFile: File?

    private val channel: FileChannel

    /** Takes ownership of `pfd`, closing this source closes it. */
    constructor(pfd: ParcelFileDescriptor, name: String) {
        this.name = name
        this.localFile = null
        this.channel = ParcelFileDescriptor.AutoCloseInputStream(pfd).getChannel()
    }

    @Throws(IOException::class)
    constructor(file: File) {
        this.name = file.getName()
        this.localFile = file.getAbsoluteFile()
        this.channel = FileChannel.`open`(file.toPath(), StandardOpenOption.READ)
    }

    fun name(): String {
        return name
    }

    /* A picker descriptor grants reads through the channel, not access to reopen its backing path. */
    fun localFile(): File? {
        return localFile
    }

    fun channel(): FileChannel {
        return channel
    }

    @Throws(IOException::class)
    fun size(): Long {
        return channel.size()
    }

    /** How far the stream readers got, in bytes of this file. */
    fun consumed(): Long {
        try {
            return channel.position()
        } catch (e: IOException) {
            return 0L
        }
    }

    /** Up to `length` bytes at `position`, without moving the read position. */
    @Throws(IOException::class)
    fun readAt(position: Long, length: Int): ByteArray {
        val buffer: ByteBuffer = ByteBuffer.allocate(length)
        var total: Int = 0
        while ((total < length)) {
            val read: Int = channel.read(buffer, (position + (total).toLong()))
            if ((read <= 0)) {
                break
            }
            total += read
        }
        return (if ((total == length)) buffer.array() else Arrays.copyOf(buffer.array(), total))
    }

    /** A new stream from the start of the file. Closing it leaves this source open. */
    @Throws(IOException::class)
    fun openStream(): InputStream {
        channel.position(0L)
        return ChannelStream(channel)
    }

    @Throws(IOException::class)
    override fun close() {
        channel.close()
    }

    private class ChannelStream : InputStream {
        private val channel: FileChannel

        constructor(channel: FileChannel) : super() {
            this.channel = channel
        }

        @Throws(IOException::class)
        override fun read(): Int {
            val one: ByteArray = ByteArray(1)
            val read: Int = read(one, 0, 1)
            return (if ((read < 0)) -1 else ((one[0]).toInt() and (0xFf).toInt()))
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if ((length == 0)) {
                return 0
            }
            return channel.read(ByteBuffer.wrap(buffer, offset, length))
        }
    }
}
