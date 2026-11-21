package com.ditzzy.dsunext.workspace;

import android.os.ParcelFileDescriptor;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.ArrayList;

import static org.junit.Assert.*;

/* Magic fixtures exercise input preparation without loading native code or a full GSI image. */
@RunWith(AndroidJUnit4.class)
public class ImageImporterDeviceTest {
    private File root;
    private File staging;

    @Before public void setUp() throws IOException {
        assertTrue("This test must run as an app UID", Process.myUid() >= 10_000);
        root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "image-import-test-").toFile();
        staging = new File(root, ".staging");
    }

    @After public void tearDown() throws IOException {
        if (root != null) WorkspaceStore.deleteTree(root.toPath());
    }

    @Test public void stagesAnExt4DescriptorAndKeepsTheCopyAfterTheSourceCloses() throws Exception {
        byte[] bytes = rawImage(false);
        File selected = write("system.img", bytes);
        Reporter reporter = new Reporter();

        File prepared = prepareDescriptor(selected, reporter);

        assertEquals(staging.getCanonicalFile(), prepared.getParentFile().getCanonicalFile());
        assertFalse(prepared.getPath().startsWith("/proc/"));
        assertArrayEquals(bytes, Files.readAllBytes(prepared.toPath()));
        assertArrayEquals(bytes, Files.readAllBytes(selected.toPath()));
        assertTrue(selected.delete());
        assertArrayEquals(bytes, Files.readAllBytes(prepared.toPath()));
        assertFalse(reporter.progress.isEmpty());
    }

    @Test public void stagesAnErofsDescriptorBasedOnItsContents() throws Exception {
        byte[] bytes = rawImage(true);
        File selected = write("download.bin", bytes);

        File prepared = prepareDescriptor(selected, new Reporter());

        try (ImageSource source = new ImageSource(prepared)) {
            assertEquals(ImageFormat.Container.EROFS, ImageFormat.detect(source));
        }
        assertArrayEquals(bytes, Files.readAllBytes(prepared.toPath()));
    }

    @Test public void decompressesXzWithoutCopyingTheExpandedImageAgain() throws Exception {
        byte[] bytes = rawImage(false);
        File selected = new File(root, "system.img.xz");
        try (XZOutputStream output = new XZOutputStream(new FileOutputStream(selected), new LZMA2Options(0))) {
            output.write(bytes);
        }

        File prepared = prepareDescriptor(selected, new Reporter());

        assertEquals(staging.getCanonicalFile(), prepared.getParentFile().getCanonicalFile());
        assertArrayEquals(bytes, Files.readAllBytes(prepared.toPath()));
        assertEquals(1, staging.list().length);
    }

    @Test public void reusesAnAlreadyPrivateRawImage() throws Exception {
        File selected = write("private.img", rawImage(false));
        try (ImageSource source = new ImageSource(selected)) {
            File prepared = ImageImporter.prepareImage(source, staging, new Reporter());
            assertEquals(selected.getAbsoluteFile(), prepared);
        }
        assertFalse(staging.exists());
    }

    @Test public void doesNotOverwriteAnExistingStagedImage() throws Exception {
        File selected = write("system.img", rawImage(false));
        assertTrue(staging.mkdir());
        File existing = new File(staging, "raw.img");
        byte[] original = {1, 2, 3};
        Files.write(existing.toPath(), original);

        try {
            prepareDescriptor(selected, new Reporter());
            fail("An existing staged image must not be overwritten");
        } catch (FileAlreadyExistsException expected) {
            assertArrayEquals(original, Files.readAllBytes(existing.toPath()));
        }
    }

    @Test public void removesAnIncompleteCopyIfTheSourceIsTruncated() throws Exception {
        File selected = write("system.img", rawImage(false));
        Reporter reporter = new Reporter() {
            private boolean truncated;
            @Override public void progress(int percent) {
                super.progress(percent);
                if (!truncated) {
                    truncated = true;
                    try (RandomAccessFile source = new RandomAccessFile(selected, "rw")) {
                        source.setLength(0);
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                }
            }
        };
        try {
            prepareDescriptor(selected, reporter);
            fail("An incomplete copy must not be passed to extraction");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("changed while copying"));
            assertEquals(0, staging.list().length);
        }
    }

    private File prepareDescriptor(File selected, JobReporter reporter) throws IOException {
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY);
        try (ImageSource source = new ImageSource(descriptor, selected.getName())) {
            assertNull(source.localFile());
            return ImageImporter.prepareImage(source, staging, reporter);
        }
    }

    private File write(String name, byte[] bytes) throws IOException {
        File file = new File(root, name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static byte[] rawImage(boolean erofs) {
        byte[] bytes = new byte[2 * 256 * 1024 + 4096];
        if (erofs) {
            bytes[1024] = (byte) 0xE2;
            bytes[1025] = (byte) 0xE1;
            bytes[1026] = (byte) 0xF5;
            bytes[1027] = (byte) 0xE0;
        } else {
            bytes[1080] = 0x53;
            bytes[1081] = (byte) 0xEF;
        }
        bytes[bytes.length - 1] = 0x7F;
        return bytes;
    }

    private static class Reporter implements JobReporter {
        final ArrayList<Integer> progress = new ArrayList<>();
        @Override public void log(String line) {}
        @Override public void progress(int percent) { progress.add(percent); }
    }
}
