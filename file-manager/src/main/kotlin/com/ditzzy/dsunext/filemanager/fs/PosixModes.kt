package com.ditzzy.dsunext.filemanager.fs

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal object PosixModes {
    private val bits = arrayOf(
        PosixFilePermission.OWNER_READ to 256,
        PosixFilePermission.OWNER_WRITE to 128,
        PosixFilePermission.OWNER_EXECUTE to 64,
        PosixFilePermission.GROUP_READ to 32,
        PosixFilePermission.GROUP_WRITE to 16,
        PosixFilePermission.GROUP_EXECUTE to 8,
        PosixFilePermission.OTHERS_READ to 4,
        PosixFilePermission.OTHERS_WRITE to 2,
        PosixFilePermission.OTHERS_EXECUTE to 1,
    )

    fun read(path: Path): Int? {
        if (path.fileSystem.supportedFileAttributeViews().contains("unix")) {
            return (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 4095
        }
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: return null
        val permissions = view.readAttributes().permissions()
        return bits.sumOf { (permission, bit) -> if (permission in permissions) bit else 0 }
    }

    fun write(path: Path, mode: Int) {
        if (path.fileSystem.supportedFileAttributeViews().contains("unix")) {
            Files.setAttribute(path, "unix:mode", mode, NOFOLLOW_LINKS)
            return
        }
        if ((mode and 3584) != 0) throw UnsupportedOperationException("Special POSIX mode bits are unavailable")
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?: throw UnsupportedOperationException("POSIX permissions are unavailable")
        view.setPermissions(bits.filter { (_, bit) -> (mode and bit) != 0 }.map { it.first }.toSet())
    }
}
