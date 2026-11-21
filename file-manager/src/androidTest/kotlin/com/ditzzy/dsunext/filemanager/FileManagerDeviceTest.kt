package com.ditzzy.dsunext.filemanager

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditzzy.dsunext.filemanager.api.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/* Host JVM tests do not run under Android's app SELinux policy. Use private app storage here. */
@RunWith(AndroidJUnit4::class)
class FileManagerDeviceTest {
    private lateinit var root: File
    private lateinit var manager: FileManager

    @Before
    fun setUp() {
        assertTrue("This test must run as an app UID", Process.myUid() >= 10_000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(context.filesDir.toPath(), "file-manager-test-").toFile()
        manager = FileManager(root)
    }

    @After
    fun tearDown() {
        if (::manager.isInitialized) manager.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun importsABootAnimationIntoMediaWithoutChangingDirectoryPermissions() {
        val media = File(root, "system/media")
        assertTrue(media.mkdirs())
        val permissions = Files.getPosixFilePermissions(media.toPath())
        val source = File(root, "import.zip")
        ZipOutputStream(source.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("desc.txt"))
            zip.write("1080 1920 30\np 1 0 part0\n".toByteArray())
            zip.closeEntry()
        }

        val result = manager.execute(FileRequest.CreateFile("system/media/bootanimation.zip", InputSource {
            source.inputStream()
        }))

        success(result)
        assertArrayEquals(source.readBytes(), File(media, "bootanimation.zip").readBytes())
        assertEquals(permissions, Files.getPosixFilePermissions(media.toPath()))
        assertEquals(listOf("bootanimation.zip"), media.list()!!.toList())
    }

    @Test
    fun createsEmptyFilesAndCopiesFilesInPrivateStorage() {
        success(manager.execute(FileRequest.CreateFile("empty")))
        assertEquals(0L, File(root, "empty").length())
        File(root, "source").writeText("content")

        success(manager.execute(FileRequest.Copy("source", "copied")))

        assertEquals("content", File(root, "copied").readText())
        assertFalse(root.list()!!.any { it.startsWith(".file-manager-") })
    }

    @Test
    fun conflictPoliciesPreserveExistingFilesAndOnlyReplaceWhenRequested() {
        File(root, "data").writeText("original")
        val denied = manager.execute(FileRequest.CreateFile("data"))
        assertTrue(denied is FileResult.Failure)
        assertEquals(FileErrorCode.ALREADY_EXISTS, (denied as FileResult.Failure).error.code)
        assertEquals("original", File(root, "data").readText())

        success(manager.execute(FileRequest.CreateFile("data", InputSource {
            ByteArrayInputStream("second".toByteArray())
        }, conflict = ConflictPolicy.KEEP_BOTH)))
        assertEquals("second", File(root, "data (1)").readText())
        assertEquals("original", File(root, "data").readText())

        success(manager.execute(FileRequest.WriteFile("data", InputSource {
            ByteArrayInputStream("replacement".toByteArray())
        })))
        assertEquals("replacement", File(root, "data").readText())
        assertFalse(root.list()!!.any { it.startsWith(".file-manager-") })
    }

    private fun success(result: FileResult) {
        assertTrue(result.toString(), result is FileResult.Success)
    }
}
