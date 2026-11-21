package com.ditzzy.dsunext.filemanager

import com.ditzzy.dsunext.filemanager.api.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FileManagerTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var manager: FileManager

    @Before
    fun setUp() {
        root = temporary.newFolder("workspace")
        manager = FileManager(root)
    }

    @After
    fun tearDown() {
        manager.close()
    }

    @Test
    fun createsEmptyFilesAndParents() {
        success(manager.execute(FileRequest.CreateFile("a/b/empty", createParents = true)))
        assertTrue(File(root, "a/b/empty").isFile)
        assertEquals(0L, File(root, "a/b/empty").length())
    }

    @Test
    fun streamsContentInAndOut() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        success(manager.execute(FileRequest.CreateFile("data", InputSource { ByteArrayInputStream(data) })))
        val output = ByteArrayOutputStream()
        val result = success(manager.execute(FileRequest.ReadFile("data", OutputTarget { output })))
        assertArrayEquals(data, output.toByteArray())
        assertEquals(data.size.toLong(), result.report.outputBytes)
    }

    @Test
    fun conflictsDoNotOpenTheInputStreamOrOverwriteTheFile() {
        File(root, "data").writeText("original")
        var opened = false
        val result = manager.execute(FileRequest.CreateFile("data", InputSource {
            opened = true
            ByteArrayInputStream(byteArrayOf())
        }))
        assertEquals(FileErrorCode.ALREADY_EXISTS, failure(result).code)
        assertFalse(opened)
        assertEquals("original", File(root, "data").readText())
    }

    @Test
    fun skipAndKeepBothPreserveTheOriginal() {
        File(root, "file.txt").writeText("old")
        val skipped = success(manager.execute(FileRequest.CreateFile("file.txt", conflict = ConflictPolicy.SKIP)))
        assertEquals(1L, skipped.report.skippedCount)
        assertNull((skipped.value as FileValue.Destination).path)
        val copied = success(manager.execute(FileRequest.CreateFile("file.txt", text("new"), conflict = ConflictPolicy.KEEP_BOTH)))
        assertEquals("file (1).txt", (copied.value as FileValue.Destination).path)
        assertEquals("old", File(root, "file.txt").readText())
        assertEquals("new", File(root, "file (1).txt").readText())
    }

    @Test
    fun aFailedReplacementKeepsTheOriginalAndCleansStagingFiles() {
        File(root, "data").writeText("original")
        val result = manager.execute(FileRequest.WriteFile("data", InputSource { brokenInput() }))
        assertTrue(result is FileResult.Failure)
        assertEquals("original", File(root, "data").readText())
        assertEquals(listOf("data"), root.list()!!.sorted())
    }

    @Test
    fun replacesContentAfterTheStreamWasCompletelyWrittenAndClosed() {
        File(root, "data").writeText("old")
        var closed = false
        success(manager.execute(FileRequest.WriteFile("data", InputSource {
            object : ByteArrayInputStream("new".toByteArray()) {
                override fun close() {
                    assertEquals("old", File(root, "data").readText())
                    closed = true
                    super.close()
                }
            }
        })))
        assertTrue(closed)
        assertEquals("new", File(root, "data").readText())
        assertEquals(listOf("data"), root.list()!!.sorted())
    }

    @Test
    fun detectsContentChangedBeforeReplacement() {
        File(root, "data").writeText("old")
        val result = manager.execute(FileRequest.WriteFile("data", InputSource {
            File(root, "data").writeText("changed externally")
            ByteArrayInputStream("new".toByteArray())
        }))
        assertEquals(FileErrorCode.SOURCE_CHANGED, failure(result).code)
        assertEquals("changed externally", File(root, "data").readText())
    }

    @Test
    fun cancellationBeforeCommitKeepsTheOriginal() {
        File(root, "data").writeText("original")
        val token = CancellationToken()
        val result = manager.execute(FileRequest.WriteFile("data", InputSource {
            object : ByteArrayInputStream(ByteArray(128_000)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    token.cancel()
                    return count
                }
            }
        }), token)
        assertTrue(result is FileResult.Cancelled)
        assertEquals("original", File(root, "data").readText())
        assertEquals(listOf("data"), root.list()!!.sorted())
    }

    @Test
    fun failureReportsParentsAlreadyCreated() {
        val result = manager.execute(FileRequest.CreateFile("a/b/data", InputSource { brokenInput() }, createParents = true))
        assertTrue(result is FileResult.Partial)
        assertEquals(2L, result.report.changeCount)
        assertFalse(File(root, "a/b/data").exists())
    }

    @Test
    fun rootCanBeListedAndInspectedButCannotBeDeleted() {
        File(root, "data").writeText("hello")
        val listed = success(manager.execute(FileRequest.ListDirectory())).value as FileValue.Listing
        assertEquals(listOf("data"), listed.entries.map { it.path })
        success(manager.execute(FileRequest.Stat("")))
        assertEquals(FileErrorCode.INVALID_PATH, failure(manager.execute(FileRequest.Delete("", true))).code)
        assertTrue(root.isDirectory)
    }

    @Test
    fun absoluteAndParentTraversalPathsAreRejected() {
        assertEquals(FileErrorCode.OUTSIDE_WORKSPACE, failure(manager.execute(FileRequest.CreateFile("../escape"))).code)
        assertEquals(FileErrorCode.OUTSIDE_WORKSPACE, failure(manager.execute(FileRequest.Stat(root.absolutePath))).code)
        assertEquals(FileErrorCode.OUTSIDE_WORKSPACE, failure(manager.execute(FileRequest.Stat("a/../data"))).code)
    }

    @Test
    fun refusesToTraverseOrReadSymlinks() {
        val outside = temporary.newFolder("outside")
        File(outside, "secret").writeText("untouched")
        link(File(root, "link").toPath(), outside.toPath())
        val result = manager.execute(FileRequest.CreateFile("link/escape"))
        assertEquals(FileErrorCode.SYMLINK_NOT_ALLOWED, failure(result).code)
        assertFalse(File(outside, "escape").exists())
        val info = success(manager.execute(FileRequest.Stat("link"))).value as FileValue.Entry
        assertEquals(FileKind.SYMLINK, info.entry.kind)
        assertEquals(FileErrorCode.SYMLINK_NOT_ALLOWED,
            failure(manager.execute(FileRequest.ReadFile("link", OutputTarget { ByteArrayOutputStream() }))).code)
    }

    @Test
    fun aDanglingDestinationStillCountsAsAnExistingFile() {
        link(File(root, "dangling").toPath(), root.toPath().resolve("missing"))
        assertEquals(FileErrorCode.ALREADY_EXISTS,
            failure(manager.execute(FileRequest.CreateFile("dangling"))).code)
        assertEquals(FileErrorCode.TYPE_MISMATCH,
            failure(manager.execute(FileRequest.CreateFile("dangling", conflict = ConflictPolicy.REPLACE))).code)
        assertTrue(Files.isSymbolicLink(File(root, "dangling").toPath()))
    }

    @Test
    fun copyingAndDeletingSymlinksDoesNotTouchTheirTargets() {
        val outside = temporary.newFolder("outside")
        File(outside, "keep").writeText("untouched")
        link(File(root, "original").toPath(), outside.toPath())
        success(manager.execute(FileRequest.Copy("original", "copied")))
        assertTrue(Files.isSymbolicLink(File(root, "copied").toPath()))
        assertEquals(outside.toPath(), Files.readSymbolicLink(File(root, "copied").toPath()))
        success(manager.execute(FileRequest.Delete("copied", recursive = true)))
        assertEquals("untouched", File(outside, "keep").readText())
    }

    @Test
    fun createsDanglingLinks() {
        success(manager.execute(FileRequest.CreateSymlink("dangling", "/system/bin/missing")))
        assertTrue(Files.exists(File(root, "dangling").toPath(), NOFOLLOW_LINKS))
        assertEquals("/system/bin/missing", Files.readSymbolicLink(File(root, "dangling").toPath()).toString())
    }

    @Test
    fun recursiveCopyAndDeleteHandleAnEntireTree() {
        File(root, "source/nested").mkdirs()
        File(root, "source/nested/data").writeText("hello")
        success(manager.execute(FileRequest.Copy("source", "destination")))
        assertEquals("hello", File(root, "destination/nested/data").readText())
        assertEquals(FileErrorCode.DIRECTORY_NOT_EMPTY,
            failure(manager.execute(FileRequest.Delete("destination"))).code)
        success(manager.execute(FileRequest.Delete("destination", recursive = true)))
        assertFalse(File(root, "destination").exists())
        assertTrue(File(root, "source/nested/data").isFile)
    }

    @Test
    fun directoryReplacementRequiresAnExplicitMerge() {
        File(root, "source").mkdir()
        File(root, "destination").mkdir()
        File(root, "source/new").writeText("new")
        File(root, "destination/keep").writeText("keep")
        assertEquals(FileErrorCode.ALREADY_EXISTS,
            failure(manager.execute(FileRequest.Copy("source", "destination", ConflictPolicy.REPLACE))).code)
        success(manager.execute(FileRequest.Copy("source", "destination", directories = DirectoryPolicy.MERGE)))
        assertEquals("new", File(root, "destination/new").readText())
        assertEquals("keep", File(root, "destination/keep").readText())
    }

    @Test
    fun keepBothRoutesChildrenIntoTheRenamedDirectory() {
        File(root, "source/nested").mkdirs()
        File(root, "source/nested/data").writeText("new")
        File(root, "destination").mkdir()
        File(root, "destination/keep").writeText("keep")
        val result = success(manager.execute(FileRequest.Copy("source", "destination", ConflictPolicy.KEEP_BOTH)))
        assertEquals("destination (1)", (result.value as FileValue.Destination).path)
        assertEquals("new", File(root, "destination (1)/nested/data").readText())
        assertEquals(listOf("keep"), File(root, "destination").list()!!.toList())
    }

    @Test
    fun aCompleteMergedMoveRemovesItsSourceAndKeepsExistingDestinationEntries() {
        File(root, "source/nested").mkdirs()
        File(root, "source/nested/data").writeText("hello")
        File(root, "destination").mkdir()
        File(root, "destination/keep").writeText("keep")
        success(manager.execute(FileRequest.Move("source", "destination", directories = DirectoryPolicy.MERGE)))
        assertFalse(File(root, "source").exists())
        assertEquals("hello", File(root, "destination/nested/data").readText())
        assertEquals("keep", File(root, "destination/keep").readText())
    }

    @Test
    fun rejectsCopyingOntoTheSameFileAndOverlappingDirectories() {
        File(root, "source/nested").mkdirs()
        File(root, "source/data").writeText("hello")
        assertEquals(FileErrorCode.SAME_FILE, failure(manager.execute(FileRequest.Copy("source/data", "source/data"))).code)
        assertEquals(FileErrorCode.DESCENDANT_TARGET, failure(manager.execute(FileRequest.Copy("source", "source/nested/new"))).code)
        assertEquals(FileErrorCode.DESCENDANT_TARGET, failure(manager.execute(FileRequest.Copy("source/nested", "source"))).code)
    }

    @Test
    fun movesAndRenamesFilesWithoutKeepingTheOldPath() {
        File(root, "original").writeText("hello")
        success(manager.execute(FileRequest.Move("original", "nested/moved", createParents = true)))
        assertFalse(File(root, "original").exists())
        success(manager.execute(FileRequest.Rename("nested/moved", "renamed")))
        assertEquals("hello", File(root, "nested/renamed").readText())
        assertEquals(FileErrorCode.INVALID_PATH, failure(manager.execute(FileRequest.Rename("nested/renamed", "../escape"))).code)
    }

    @Test
    fun aMoveConflictKeepsBothExistingFilesUntouched() {
        File(root, "source").writeText("source")
        File(root, "destination").writeText("destination")
        assertEquals(FileErrorCode.ALREADY_EXISTS, failure(manager.execute(FileRequest.Move("source", "destination"))).code)
        assertEquals("source", File(root, "source").readText())
        assertEquals("destination", File(root, "destination").readText())
    }

    @Test
    fun mergedMoveKeepsItsSourceWhenChildrenWereSkipped() {
        File(root, "source").mkdir()
        File(root, "destination").mkdir()
        File(root, "source/conflict").writeText("source")
        File(root, "source/new").writeText("new")
        File(root, "destination/conflict").writeText("destination")
        val result = manager.execute(FileRequest.Move("source", "destination",
            ConflictPolicy.SKIP, DirectoryPolicy.MERGE))
        assertTrue(result.toString(), result is FileResult.Partial)
        assertTrue(File(root, "source/new").exists())
        assertEquals("new", File(root, "destination/new").readText())
        assertEquals("destination", File(root, "destination/conflict").readText())
    }

    @Test
    fun permissionChangesAndContentReplacementKeepTheRequestedMode() {
        org.junit.Assume.assumeTrue(root.toPath().fileSystem.supportedFileAttributeViews().contains("posix"))
        File(root, "data").writeText("old")
        success(manager.execute(FileRequest.SetPermissions("data", 420)))
        success(manager.execute(FileRequest.WriteFile("data", text("new"))))
        val info = success(manager.execute(FileRequest.Stat("data"))).value as FileValue.Entry
        assertEquals(420, info.entry.mode)
        assertEquals("new", File(root, "data").readText())
        assertEquals(FileErrorCode.INVALID_MODE, failure(manager.execute(FileRequest.SetPermissions("data", -1))).code)
    }

    @Test
    fun recursivePermissionsSkipLinksAndLeaveTheirTargetsAlone() {
        org.junit.Assume.assumeTrue(root.toPath().fileSystem.supportedFileAttributeViews().contains("posix"))
        val outside = temporary.newFile("outside-data")
        val original = Files.getPosixFilePermissions(outside.toPath())
        File(root, "tree").mkdir()
        File(root, "tree/data").writeText("hello")
        link(File(root, "tree/link").toPath(), outside.toPath())
        val result = success(manager.execute(FileRequest.SetPermissions("tree", 448, recursive = true)))
        assertEquals(1L, result.report.skippedCount)
        assertEquals(original, Files.getPosixFilePermissions(outside.toPath()))
        val info = success(manager.execute(FileRequest.Stat("tree/data"))).value as FileValue.Entry
        assertEquals(448, info.entry.mode)
    }

    @Test
    fun callbackExceptionsDoNotFailTheOperation() {
        success(manager.execute(FileRequest.CreateFile("data"), progress = ProgressListener { throw IllegalStateException("UI") }))
        assertTrue(File(root, "data").isFile)
    }

    @Test
    fun progressCallbacksCannotReenterAnActiveWorkspaceOperation() {
        var nested: FileResult? = null
        success(manager.execute(FileRequest.CreateFile("data"), progress = ProgressListener {
            if (it.stage == OperationStage.STARTED) nested = manager.execute(FileRequest.Delete("data"))
        }))
        assertEquals(FileErrorCode.BUSY, failure(nested!!).code)
        assertTrue(File(root, "data").isFile)
    }

    @Test
    fun reportsCapSamplesWithoutLosingCounts() {
        File(root, "source").mkdir()
        repeat(300) { File(root, "source/$it").createNewFile() }
        val result = success(manager.execute(FileRequest.Copy("source", "destination")))
        assertEquals(301L, result.report.changeCount)
        assertEquals(256, result.report.changes.size)
        assertEquals(300, File(root, "destination").list()!!.size)
    }

    @Test
    fun anOutputFailureIsPartialBecauseTheSinkMayAlreadyBeChanged() {
        File(root, "data").writeText("hello")
        val result = manager.execute(FileRequest.ReadFile("data", OutputTarget { throw IOException("sink") }))
        assertTrue(result is FileResult.Partial)
        assertTrue(result.report.outputOpened)
        assertEquals("hello", File(root, "data").readText())
    }

    @Test
    fun queuedCancellationStillDeliversAResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = manager.submit(FileRequest.CreateFile("first", InputSource {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ByteArrayInputStream(byteArrayOf())
        }))
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = manager.submit(FileRequest.CreateFile("second"))
            assertTrue(second.cancel())
            release.countDown()
            success(first.await(5, TimeUnit.SECONDS))
            assertTrue(second.await(5, TimeUnit.SECONDS) is FileResult.Cancelled)
            assertFalse(File(root, "second").exists())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun closingAnActiveManagerCompletesBothActiveAndQueuedHandles() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = manager.submit(FileRequest.CreateFile("first", InputSource {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ByteArrayInputStream(byteArrayOf())
        }))
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = manager.submit(FileRequest.CreateFile("second"))
            manager.close()
            release.countDown()
            assertTrue(first.await(5, TimeUnit.SECONDS) is FileResult.Cancelled)
            assertTrue(second.await(5, TimeUnit.SECONDS) is FileResult.Cancelled)
            assertFalse(File(root, "first").exists())
            assertFalse(File(root, "second").exists())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun managersForTheSameRootShareAnOperationLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondManager = FileManager(root)
        val first = manager.submit(FileRequest.CreateFile("data", InputSource {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ByteArrayInputStream("hello".toByteArray())
        }))
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = secondManager.submit(FileRequest.Stat("data"), ProgressListener {
                if (it.stage == OperationStage.STARTED) secondStarted.countDown()
            })
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            success(first.await(5, TimeUnit.SECONDS))
            success(second.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            secondManager.close()
        }
    }

    @Test
    fun closedManagersReturnStructuredFailures() {
        manager.close()
        assertEquals(FileErrorCode.CLOSED, failure(manager.execute(FileRequest.Stat(""))).code)
        assertEquals(FileErrorCode.CLOSED, failure(manager.submit(FileRequest.Stat("")).await()).code)
    }

    private fun success(result: FileResult): FileResult.Success {
        assertTrue(result.toString(), result is FileResult.Success)
        return result as FileResult.Success
    }

    private fun failure(result: FileResult): FileError {
        assertTrue(result.toString(), result is FileResult.Failure)
        return (result as FileResult.Failure).error
    }

    private fun text(value: String) = InputSource { ByteArrayInputStream(value.toByteArray()) }

    private fun brokenInput(): InputStream = object : InputStream() {
        override fun read(): Int = throw IOException("source failed")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("source failed")
    }

    private fun link(path: Path, target: Path) {
        try {
            Files.createSymbolicLink(path, target)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }
    }
}
