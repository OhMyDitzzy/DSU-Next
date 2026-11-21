package com.ditzzy.dsunext.workspace;

import com.ditzzy.dsunext.filemanager.api.ChangeKind;
import com.ditzzy.dsunext.filemanager.api.FileChange;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class GsiMetadataTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File workspace;
    private Path root;
    private Path config;

    @Before public void setUp() throws Exception {
        workspace = temporary.newFolder("workspace");
        root = Files.createDirectory(new File(workspace, "system").toPath());
        config = Files.createDirectory(new File(workspace, "config").toPath());
        write("info", "mount_point=/\n");
        write("fs_config", "/ 0 0 0755\nold 0 0 0755\nold/data 1000 2000 0640 capabilities=0x1\nold/link 0 0 0777\n");
        write("file_contexts", "/ u:object_r:rootfs:s0\n/old u:object_r:system_file:s0\n/old/data u:object_r:system_file:s0\n/old/link u:object_r:system_file:s0\n");
        write("symlinks", "old/link /system/bin/sh\n");
        Files.createDirectory(root.resolve("old"));
        Files.write(root.resolve("old/data"), new byte[]{1});
    }

    @Test public void directoryRenamePreservesOwnersCapabilitiesLabelsAndLinks() throws Exception {
        GsiMetadata metadata = new GsiMetadata(workspace, "system");
        metadata.begin();
        Files.move(root.resolve("old"), root.resolve("new"));
        metadata.apply(new FileChange(ChangeKind.MOVED, "new", "old"));
        metadata.save();
        assertTrue(read("fs_config").contains("new/data 1000 2000 0640 capabilities=0x1"));
        assertFalse(read("fs_config").contains("old/data"));
        assertTrue(read("file_contexts").contains("/new/data u:object_r:system_file:s0"));
        assertEquals("new/link /system/bin/sh\n", read("symlinks"));
    }

    @Test public void deletingALinkRemovesItsSidecarEntry() throws Exception {
        GsiMetadata metadata = new GsiMetadata(workspace, "system");
        metadata.begin();
        metadata.apply(new FileChange(ChangeKind.DELETED, "old/link", null));
        metadata.save();
        assertEquals("", read("symlinks"));
        assertFalse(read("fs_config").contains("old/link"));
    }

    @Test public void partialCopyOnlyRecordsEntriesThatActuallyCompleted() throws Exception {
        GsiMetadata metadata = new GsiMetadata(workspace, "system");
        metadata.begin();
        Files.createDirectory(root.resolve("copy"));
        metadata.apply(new FileChange(ChangeKind.COPIED, "copy", "old"));
        Files.copy(root.resolve("old/data"), root.resolve("copy/data"));
        metadata.apply(new FileChange(ChangeKind.COPIED, "copy/data", "old/data"));
        metadata.save();
        assertTrue(read("fs_config").contains("copy/data 1000 2000 0640 capabilities=0x1"));
        assertFalse(read("symlinks").contains("copy/link"));
        assertTrue(read("fs_config").contains("old/data"));
    }

    @Test public void copyThenDeleteMoveKeepsTheMetadataAlreadyCopied() throws Exception {
        GsiMetadata metadata = new GsiMetadata(workspace, "system");
        metadata.begin();
        Files.createDirectory(root.resolve("copy"));
        Files.copy(root.resolve("old/data"), root.resolve("copy/data"));
        metadata.apply(new FileChange(ChangeKind.COPIED, "copy", "old"));
        metadata.apply(new FileChange(ChangeKind.COPIED, "copy/data", "old/data"));
        metadata.apply(new FileChange(ChangeKind.DELETED, "old/data", null));
        metadata.apply(new FileChange(ChangeKind.DELETED, "old", null));
        metadata.apply(new FileChange(ChangeKind.MOVED, "copy", "old"));
        metadata.save();
        assertTrue(read("fs_config").contains("copy/data 1000 2000 0640 capabilities=0x1"));
        assertFalse(read("fs_config").contains("old/data"));
    }

    @Test public void permissionEditsLeaveWorkingFilesAccessible() throws Exception {
        Path data = root.resolve("old/data");
        Object before = Files.getAttribute(data, "unix:mode");
        GsiMetadata metadata = new GsiMetadata(workspace, "system");
        metadata.begin();
        metadata.setMode("old/data", 0);
        metadata.save();
        assertEquals(before, Files.getAttribute(data, "unix:mode"));
        assertEquals(0, new GsiMetadata(workspace, "system").mode("old/data", false, false));
    }

    @Test public void interruptedCommitBlocksRepackButLeavesMetadataReadable() throws Exception {
        GsiMetadata metadata = new GsiMetadata(workspace, "system");
        metadata.begin();
        new GsiMetadata(workspace, "system");
        try {
            GsiMetadata.checkHealthy(workspace);
            fail("An incomplete metadata commit must block repack");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("recovery"));
        }
        metadata.save();
        GsiMetadata.checkHealthy(workspace);
    }

    @Test public void aSystemMountPointMapsRelativePathsCorrectly() throws Exception {
        write("info", "mount_point=/system\n");
        write("fs_config", "system 0 0 0755\nsystem/old/data 1000 2000 0640\n");
        write("file_contexts", "/system u:object_r:system_file:s0\n");
        write("symlinks", "");
        GsiMetadata metadata = new GsiMetadata(workspace, "system");
        metadata.begin();
        metadata.setMode("old/data", 0600);
        metadata.save();
        assertTrue(read("fs_config").contains("system/old/data 1000 2000 0600"));
        assertFalse(read("fs_config").contains("system/system/"));
    }

    @Test public void escapedLabelsRoundTripWithoutChangingTheirPaths() throws Exception {
        write("file_contexts", "/ u:object_r:rootfs:s0\n/old/libc\\+\\+\\.so u:object_r:system_file:s0\n");
        GsiMetadata metadata = new GsiMetadata(workspace, "system");
        metadata.begin(); metadata.save();
        assertTrue(read("file_contexts").contains("/old/libc\\+\\+\\.so u:object_r:system_file:s0"));
    }

    private void write(String suffix, String text) throws IOException {
        Files.write(config.resolve("system_" + suffix), text.getBytes(StandardCharsets.UTF_8));
    }
    private String read(String suffix) throws IOException {
        return new String(Files.readAllBytes(config.resolve("system_" + suffix)), StandardCharsets.UTF_8);
    }
}
