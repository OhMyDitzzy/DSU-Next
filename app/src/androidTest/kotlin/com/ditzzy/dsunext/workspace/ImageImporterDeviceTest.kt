package com.ditzzy.dsunext.workspace

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.ArrayList
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
open class ImageImporterDeviceTest {
    private var root: File? = null

    private var staging: File? = null

    @Throws(IOException::class)
    @Before
    open fun setUp() {
        assertTrue("This test must run as an app UID", (Process.myUid() >= 10000))
        root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir().toPath(), "image-import-test-").toFile()
        staging = File(root, ".staging")
    }

    @Throws(IOException::class)
    @After
    open fun tearDown() {
        if ((root != null)) {
            WorkspaceStore.deleteTree(root!!.toPath())
        }
    }

    @Throws(Exception::class)
    @Test
    open fun stagesAnExt4DescriptorAndKeepsTheCopyAfterTheSourceCloses() {
        val bytes: ByteArray = rawImage(false)
        val selected: File = write("system.img", bytes)
        val reporter: Reporter = Reporter()
        val prepared: File = prepareDescriptor(selected, reporter)
        assertEquals(staging!!.getCanonicalFile(), prepared.getParentFile()!!.getCanonicalFile())
        assertFalse(prepared.getPath()!!.startsWith("/proc/"))
        assertArrayEquals(bytes, Files.readAllBytes(prepared.toPath()))
        assertArrayEquals(bytes, Files.readAllBytes(selected.toPath()))
        assertTrue(selected.delete())
        assertArrayEquals(bytes, Files.readAllBytes(prepared.toPath()))
        assertFalse(reporter.progress.isEmpty())
    }

    @Throws(Exception::class)
    @Test
    open fun stagesAnErofsDescriptorBasedOnItsContents() {
        val bytes: ByteArray = rawImage(true)
        val selected: File = write("download.bin", bytes)
        val prepared: File = prepareDescriptor(selected, Reporter())
        ImageSource(prepared).use { source ->
            assertEquals(ImageFormat.Container.EROFS, ImageFormat.detect(source))
        }
        assertArrayEquals(bytes, Files.readAllBytes(prepared.toPath()))
    }

    @Throws(Exception::class)
    @Test
    open fun decompressesXzWithoutCopyingTheExpandedImageAgain() {
        val bytes: ByteArray = rawImage(false)
        val selected: File = File(root, "system.img.xz")
        XZOutputStream(FileOutputStream(selected), LZMA2Options(0)).use { output ->
            output.write(bytes)
        }
        val prepared: File = prepareDescriptor(selected, Reporter())
        assertEquals(staging!!.getCanonicalFile(), prepared.getParentFile()!!.getCanonicalFile())
        assertArrayEquals(bytes, Files.readAllBytes(prepared.toPath()))
        assertEquals(1, staging!!.list().size)
    }

    @Throws(Exception::class)
    @Test
    open fun reusesAnAlreadyPrivateRawImage() {
        val selected: File = write("private.img", rawImage(false))
        ImageSource(selected).use { source ->
            val prepared: File = ImageImporter.prepareImage(source, staging!!, Reporter())
            assertEquals(selected.getAbsoluteFile(), prepared)
        }
        assertFalse(staging!!.exists())
    }

    @Throws(Exception::class)
    @Test
    open fun doesNotOverwriteAnExistingStagedImage() {
        val selected: File = write("system.img", rawImage(false))
        assertTrue(staging!!.mkdir())
        val existing: File = File(staging, "raw.img")
        val original: ByteArray = byteArrayOf(1.toByte(), 2.toByte(), 3.toByte())
        Files.write(existing.toPath(), original)
        try {
            prepareDescriptor(selected, Reporter())
            fail("An existing staged image must not be overwritten")
        } catch (expected: FileAlreadyExistsException) {
            assertArrayEquals(original, Files.readAllBytes(existing.toPath()))
        }
    }

    @Throws(Exception::class)
    @Test
    open fun removesAnIncompleteCopyIfTheSourceIsTruncated() {
        val selected: File = write("system.img", rawImage(false))
        val reporter: Reporter = object : Reporter() {
            private var truncated: Boolean = false

            override fun progress(percent: Int) {
                super.progress(percent)
                if (!truncated) {
                    truncated = true
                    try {
                        RandomAccessFile(selected, "rw").use { source ->
                            source.setLength(0)
                        }
                    } catch (error: IOException) {
                        throw IllegalStateException(error)
                    }
                }
            }
        }
        try {
            prepareDescriptor(selected, reporter)
            fail("An incomplete copy must not be passed to extraction")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("changed while copying"))
            assertEquals(0, staging!!.list().size)
        }
    }

    @Throws(IOException::class)
    private fun prepareDescriptor(selected: File, reporter: JobReporter): File {
        val descriptor: ParcelFileDescriptor = ParcelFileDescriptor.`open`(selected, ParcelFileDescriptor.MODE_READ_ONLY)
        ImageSource(descriptor, selected.getName()).use { source ->
            assertNull(source.localFile())
            return ImageImporter.prepareImage(source, staging!!, reporter)
        }
    }

    @Throws(IOException::class)
    private fun write(name: String, bytes: ByteArray): File {
        val file: File = File(root, name)
        Files.write(file.toPath(), bytes)
        return file
    }

    private open class Reporter : JobReporter {
        val progress: ArrayList<Int> = ArrayList<Int>()

        override fun log(line: String) {

        }

        override fun progress(percent: Int) {
            progress.add(percent)
        }
    }

    companion object {
        private fun rawImage(erofs: Boolean): ByteArray {
            var bytes: ByteArray = ByteArray((((2 * 256) * 1024) + 4096))
            if (erofs) {
                bytes[1024] = (0xE2).toByte()
                bytes[1025] = (0xE1).toByte()
                bytes[1026] = (0xF5).toByte()
                bytes[1027] = (0xE0).toByte()
            } else {
                bytes[1080] = (0x53).toByte()
                bytes[1081] = (0xEf).toByte()
            }
            bytes[(bytes.size - 1)] = (0x7f).toByte()
            return bytes
        }
    }
}
