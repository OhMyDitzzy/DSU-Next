package com.ditzzy.dsunext.workspace

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.ArrayList
import java.util.Enumeration
import java.util.Locale

class ArchiveExtractor {
    private fun interface StreamOpener {
        @Throws(IOException::class)
        fun `open`(): InputStream
    }

    private constructor() {

    }

    /** Reads the current entry of a 7z archive. */
    private class SevenZEntryStream : InputStream {
        private val archive: SevenZFile

        constructor(archive: SevenZFile) : super() {
            this.archive = archive
        }

        @Throws(IOException::class)
        override fun read(): Int {
            return archive.read()
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return archive.read(buffer, offset, length)
        }
    }

    /** The archive libraries close the channel they are given, the source has to outlive them. */
    private class NonClosingChannel : SeekableByteChannel {
        private val delegate: FileChannel

        constructor(delegate: FileChannel) {
            this.delegate = delegate
        }

        @Throws(IOException::class)
        override fun read(dst: ByteBuffer): Int {
            return delegate.read(dst)
        }

        override fun write(src: ByteBuffer): Int {
            throw NonWritableChannelException()
        }

        @Throws(IOException::class)
        override fun position(): Long {
            return delegate.position()
        }

        @Throws(IOException::class)
        override fun position(newPosition: Long): SeekableByteChannel {
            delegate.position(newPosition)
            return this
        }

        @Throws(IOException::class)
        override fun size(): Long {
            return delegate.size()
        }

        override fun truncate(size: Long): SeekableByteChannel {
            throw NonWritableChannelException()
        }

        override fun isOpen(): Boolean {
            return delegate.isOpen()
        }

        override fun close() {

        }
    }

    companion object {
        private const val BUFFER: Int = (256 * 1024)

        private const val PEEK: Int = 512

        private const val PAYLOAD: String = "payload.img"

        /** Images that live next to the system image in factory style archives but are not it. */
        private val SKIPPED_PREFIXES: Array<String> = arrayOf("vbmeta", "boot", "init_boot", "vendor_boot", "recovery", "dtbo", "userdata", "cache", "super", "misc", "metadata", "vendor", "product", "odm", "system_ext", "system_other")

        /** Extracts the image of the archive into `dir` and returns the file written. */
        @JvmStatic
        @Throws(IOException::class)
        fun extract(source: ImageSource, kind: ImageFormat.Container, dir: File, reporter: JobReporter): File {
            val `out`: File = File(dir, PAYLOAD)
            when (kind) {
                ImageFormat.Container.ZIP -> {
                    extractZip(source, `out`, reporter)
                }
                ImageFormat.Container.SEVEN_Z -> {
                    extractSevenZ(source, `out`, reporter)
                }
                ImageFormat.Container.GZIP, ImageFormat.Container.XZ -> {
                    extractCompressed(source, kind, `out`, reporter)
                }
                ImageFormat.Container.TAR -> {
                    extractTar(source, StreamOpener(source::openStream), `out`, reporter)
                }
                else -> {
                    throw IOException(("Not an archive: " + kind))
                }
            }
            return `out`
        }

        /** Higher is better, negative means the entry is not a candidate. */
        @JvmStatic
        fun score(entryName: String): Int {
            val base: String = entryName.substring((entryName.lastIndexOf('/') + 1)).lowercase(Locale.ROOT)
            if (!base.endsWith(".img")) {
                return -1
            }
            for (prefix in SKIPPED_PREFIXES) {
                if (base.startsWith(prefix)) {
                    return -1
                }
            }
            if (base.equals("system.img")) {
                return 100
            }
            return (if (base.startsWith("system")) 80 else 10)
        }

        @Throws(IOException::class)
        private fun extractZip(source: ImageSource, `out`: File, reporter: JobReporter) {
            ZipFile(NonClosingChannel(source.channel())).use { zip ->
                var best: ZipArchiveEntry? = null
                var bestScore: Int = -1
                val names: MutableList<String> = ArrayList<String>()
                val entries: Enumeration<ZipArchiveEntry> = zip.getEntries()
                while (entries.hasMoreElements()) {
                    val entry: ZipArchiveEntry = entries.nextElement()
                    if (entry.isDirectory()) {
                        continue
                    }
                    names.add(entry.getName())
                    val score: Int = score(entry.getName())
                    if ((score > bestScore)) {
                        best = entry
                        bestScore = score
                    }
                }
                if ((best == null)) {
                    throw noImage(names)
                }
                reporter.log((("- Extracting " + best!!.getName()) + " from the archive"))
                zip.getInputStream(best).use { `in` ->
                    copy(`in`, `out`, best!!.getSize(), source, reporter)
                }
            }
        }

        @Throws(IOException::class)
        private fun extractSevenZ(source: ImageSource, `out`: File, reporter: JobReporter) {
            SevenZFile(NonClosingChannel(source.channel())).use { archive ->
                var best: String? = null
                var bestSize: Long = -1L
                var bestScore: Int = -1
                val names: MutableList<String> = ArrayList<String>()
                for (entry in archive.getEntries()) {
                    if (entry.isDirectory()) {
                        continue
                    }
                    names.add(entry.getName())
                    val score: Int = score(entry.getName())
                    if ((score > bestScore)) {
                        best = entry.getName()
                        bestSize = entry.getSize()
                        bestScore = score
                    }
                }
                if ((best == null)) {
                    throw noImage(names)
                }
                reporter.log((("- Extracting " + best) + " from the archive"))
                var entry: SevenZArchiveEntry? = null
                while (((archive.getNextEntry()).also { entry = it } != null)) {
                    if ((!entry!!.isDirectory() && best!!.equals(entry!!.getName()))) {
                        copy(SevenZEntryStream(archive), `out`, bestSize, source, reporter)
                        return
                    }
                }
                throw IOException(("Entry not found in the archive: " + best))
            }
        }

        @Throws(IOException::class)
        private fun extractCompressed(source: ImageSource, kind: ImageFormat.Container, `out`: File, reporter: JobReporter) {
            val opener: StreamOpener = StreamOpener {
                openDecompressed(source, kind)
            }
            val head: ByteArray = ByteArray(PEEK)
            var peeked: Int = 0
            opener.`open`().use { probe ->
                peeked = readFully(probe, head)
            }
            if (ImageFormat.isTarHeader(head, peeked)) {
                extractTar(source, StreamOpener(opener::`open`), `out`, reporter)
                return
            }
            reporter.log(("- Decompressing " + source.name()))
            opener.`open`().use { `in` ->
                copy(`in`, `out`, -1L, source, reporter)
            }
        }

        @Throws(IOException::class)
        private fun openDecompressed(source: ImageSource, kind: ImageFormat.Container): InputStream {
            val raw: InputStream = source.openStream()
            val decompressed: InputStream = (if ((kind == ImageFormat.Container.GZIP)) GzipCompressorInputStream(raw, true) else XZCompressorInputStream(raw, true))
            return BufferedInputStream(decompressed, BUFFER)
        }

        @Throws(IOException::class)
        private fun extractTar(source: ImageSource, opener: StreamOpener, `out`: File, reporter: JobReporter) {
            // The first pass only picks the entry, the second one copies it
            var best: String? = null
            var bestScore: Int = -1
            val names: MutableList<String> = ArrayList<String>()
            TarArchiveInputStream(opener.`open`()).use { tar ->
                var entry: TarArchiveEntry? = null
                while (((tar.getNextTarEntry()).also { entry = it } != null)) {
                    if (!entry!!.isFile()) {
                        continue
                    }
                    names.add(entry!!.getName())
                    val score: Int = score(entry!!.getName())
                    if ((score > bestScore)) {
                        best = entry!!.getName()
                        bestScore = score
                    }
                    if ((score >= 100)) {
                        break
                    }
                }
            }
            if ((best == null)) {
                throw noImage(names)
            }
            reporter.log((("- Extracting " + best) + " from the archive"))
            TarArchiveInputStream(opener.`open`()).use { tar ->
                var entry: TarArchiveEntry? = null
                while (((tar.getNextTarEntry()).also { entry = it } != null)) {
                    if ((entry!!.isFile() && best!!.equals(entry!!.getName()))) {
                        copy(tar, `out`, entry!!.getSize(), source, reporter)
                        return
                    }
                }
            }
            throw IOException(("Entry not found in the archive: " + best))
        }

        private fun noImage(names: List<String>): IOException {
            val message: StringBuilder = StringBuilder("No system image found in the archive")
            if (!names.isEmpty()) {
                message.append(" (it holds: ")
                run {
                    var i: Int = 0
                    while (((i < names.size) && (i < 8))) {
                        if ((i > 0)) {
                            message.append(", ")
                        }
                        message.append(names.`get`(i))
                        i++
                    }
                }
                if ((names.size > 8)) {
                    message.append(", ...")
                }
                message.append(')')
            }
            return IOException(message.toString())
        }

        @Throws(IOException::class)
        private fun copy(`in`: InputStream, `out`: File, expected: Long, source: ImageSource, reporter: JobReporter) {
            val buffer: ByteArray = ByteArray(BUFFER)
            val sourceSize: Long = source.size()
            var written: Long = 0L
            var lastPercent: Int = -1
            FileOutputStream(`out`).use { output ->
                var read: Int = 0
                while (((`in`.read(buffer)).also { read = it } != -1)) {
                    output.write(buffer, 0, read)
                    written += (read).toLong()
                    var done: Long = 0L
                    var total: Long = 0L
                    if ((expected > 0L)) {
                        done = written
                        total = expected
                    } else {
                        done = source.consumed()
                        total = sourceSize
                    }
                    val percent: Int = (if ((total > 0L)) (Math.min(99L, ((done * 100L) / total))).toInt() else -1)
                    if ((percent != lastPercent)) {
                        lastPercent = percent
                        reporter.progress(percent)
                    }
                }
            }
        }

        @Throws(IOException::class)
        private fun readFully(`in`: InputStream, buffer: ByteArray): Int {
            var total: Int = 0
            while ((total < buffer.size)) {
                val read: Int = `in`.read(buffer, total, (buffer.size - total))
                if ((read < 0)) {
                    break
                }
                total += read
            }
            return total
        }
    }
}
