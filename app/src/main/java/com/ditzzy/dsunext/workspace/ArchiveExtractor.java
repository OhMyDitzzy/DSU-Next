package com.ditzzy.dsunext.workspace;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

final class ArchiveExtractor {

    private static final int BUFFER = 256 * 1024;
    private static final int PEEK = 512;
    private static final String PAYLOAD = "payload.img";

    /** Images that live next to the system image in factory style archives but are not it. */
    private static final String[] SKIPPED_PREFIXES = {
            "vbmeta", "boot", "init_boot", "vendor_boot", "recovery", "dtbo", "userdata", "cache",
            "super", "misc", "metadata", "vendor", "product", "odm", "system_ext", "system_other"
    };

    private interface StreamOpener {
        InputStream open() throws IOException;
    }

    private ArchiveExtractor() {
    }

    /** Extracts the image of the archive into {@code dir} and returns the file written. */
    static File extract(ImageSource source, ImageFormat.Container kind, File dir,
            JobReporter reporter) throws IOException {
        File out = new File(dir, PAYLOAD);
        switch (kind) {
            case ZIP:
                extractZip(source, out, reporter);
                break;
            case SEVEN_Z:
                extractSevenZ(source, out, reporter);
                break;
            case GZIP:
            case XZ:
                extractCompressed(source, kind, out, reporter);
                break;
            case TAR:
                extractTar(source, source::openStream, out, reporter);
                break;
            default:
                throw new IOException("Not an archive: " + kind);
        }
        return out;
    }

    /** Higher is better, negative means the entry is not a candidate. */
    static int score(String entryName) {
        String base = entryName.substring(entryName.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (!base.endsWith(".img")) {
            return -1;
        }
        for (String prefix : SKIPPED_PREFIXES) {
            if (base.startsWith(prefix)) {
                return -1;
            }
        }
        if (base.equals("system.img")) {
            return 100;
        }
        return base.startsWith("system") ? 80 : 10;
    }

    private static void extractZip(ImageSource source, File out, JobReporter reporter)
            throws IOException {
        try (ZipFile zip = new ZipFile(new NonClosingChannel(source.channel()))) {
            ZipArchiveEntry best = null;
            int bestScore = -1;
            List<String> names = new ArrayList<>();
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                names.add(entry.getName());
                int score = score(entry.getName());
                if (score > bestScore) {
                    best = entry;
                    bestScore = score;
                }
            }
            if (best == null) {
                throw noImage(names);
            }
            reporter.log("- Extracting " + best.getName() + " from the archive");
            try (InputStream in = zip.getInputStream(best)) {
                copy(in, out, best.getSize(), source, reporter);
            }
        }
    }

    private static void extractSevenZ(ImageSource source, File out, JobReporter reporter)
            throws IOException {
        try (SevenZFile archive = new SevenZFile(new NonClosingChannel(source.channel()))) {
            String best = null;
            long bestSize = -1L;
            int bestScore = -1;
            List<String> names = new ArrayList<>();
            for (SevenZArchiveEntry entry : archive.getEntries()) {
                if (entry.isDirectory()) {
                    continue;
                }
                names.add(entry.getName());
                int score = score(entry.getName());
                if (score > bestScore) {
                    best = entry.getName();
                    bestSize = entry.getSize();
                    bestScore = score;
                }
            }
            if (best == null) {
                throw noImage(names);
            }
            reporter.log("- Extracting " + best + " from the archive");

            // 7z archives are often solid, so entries can only be read in order
            SevenZArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (!entry.isDirectory() && best.equals(entry.getName())) {
                    copy(new SevenZEntryStream(archive), out, bestSize, source, reporter);
                    return;
                }
            }
            throw new IOException("Entry not found in the archive: " + best);
        }
    }

    private static void extractCompressed(ImageSource source, ImageFormat.Container kind,
            File out, JobReporter reporter) throws IOException {
        StreamOpener opener = () -> openDecompressed(source, kind);

        byte[] head = new byte[PEEK];
        int peeked;
        try (InputStream probe = opener.open()) {
            peeked = readFully(probe, head);
        }

        if (ImageFormat.isTarHeader(head, peeked)) {
            extractTar(source, opener::open, out, reporter);
            return;
        }
        reporter.log("- Decompressing " + source.name());
        try (InputStream in = opener.open()) {
            copy(in, out, -1L, source, reporter);
        }
    }

    private static InputStream openDecompressed(ImageSource source, ImageFormat.Container kind)
            throws IOException {
        InputStream raw = source.openStream();
        InputStream decompressed = kind == ImageFormat.Container.GZIP
                ? new GzipCompressorInputStream(raw, true)
                : new XZCompressorInputStream(raw, true);
        return new BufferedInputStream(decompressed, BUFFER);
    }

    private static void extractTar(ImageSource source, StreamOpener opener, File out,
            JobReporter reporter) throws IOException {
        // The first pass only picks the entry, the second one copies it
        String best = null;
        int bestScore = -1;
        List<String> names = new ArrayList<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(opener.open())) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (!entry.isFile()) {
                    continue;
                }
                names.add(entry.getName());
                int score = score(entry.getName());
                if (score > bestScore) {
                    best = entry.getName();
                    bestScore = score;
                }
                if (score >= 100) {
                    break;
                }
            }
        }
        if (best == null) {
            throw noImage(names);
        }
        reporter.log("- Extracting " + best + " from the archive");

        try (TarArchiveInputStream tar = new TarArchiveInputStream(opener.open())) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (entry.isFile() && best.equals(entry.getName())) {
                    copy(tar, out, entry.getSize(), source, reporter);
                    return;
                }
            }
        }
        throw new IOException("Entry not found in the archive: " + best);
    }

    private static IOException noImage(List<String> names) {
        StringBuilder message = new StringBuilder("No system image found in the archive");
        if (!names.isEmpty()) {
            message.append(" (it holds: ");
            for (int i = 0; i < names.size() && i < 8; i++) {
                if (i > 0) {
                    message.append(", ");
                }
                message.append(names.get(i));
            }
            if (names.size() > 8) {
                message.append(", ...");
            }
            message.append(')');
        }
        return new IOException(message.toString());
    }

    private static void copy(InputStream in, File out, long expected, ImageSource source,
            JobReporter reporter) throws IOException {
        byte[] buffer = new byte[BUFFER];
        long sourceSize = source.size();
        long written = 0L;
        int lastPercent = -1;
        try (OutputStream output = new FileOutputStream(out)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                written += read;

                // The uncompressed size is only known for some formats, otherwise go by how much
                // of the archive file has been consumed
                long done;
                long total;
                if (expected > 0L) {
                    done = written;
                    total = expected;
                } else {
                    done = source.consumed();
                    total = sourceSize;
                }
                int percent = total > 0L ? (int) Math.min(99L, done * 100L / total) : -1;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    reporter.progress(percent);
                }
            }
        }
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    /** Reads the current entry of a 7z archive. */
    private static final class SevenZEntryStream extends InputStream {

        private final SevenZFile archive;

        SevenZEntryStream(SevenZFile archive) {
            this.archive = archive;
        }

        @Override
        public int read() throws IOException {
            return archive.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return archive.read(buffer, offset, length);
        }
    }

    /** The archive libraries close the channel they are given, the source has to outlive them. */
    private static final class NonClosingChannel implements SeekableByteChannel {

        private final FileChannel delegate;

        NonClosingChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public int write(ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() {
            // Intentionally left open
        }
    }
}
