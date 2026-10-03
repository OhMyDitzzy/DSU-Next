package com.ditzzy.dsunext.workspace;

import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

final class ImageSource implements Closeable {

    private final String name;
    private final String path;
    private final FileChannel channel;

    /** Takes ownership of {@code pfd}, closing this source closes it. */
    ImageSource(ParcelFileDescriptor pfd, String name) {
        this.name = name;
        this.path = "/proc/self/fd/" + pfd.getFd();
        this.channel = new ParcelFileDescriptor.AutoCloseInputStream(pfd).getChannel();
    }

    ImageSource(File file) throws IOException {
        this.name = file.getName();
        this.path = file.getAbsolutePath();
        this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
    }

    String name() {
        return name;
    }

    /** A path the native code can open: {@code /proc/self/fd/N} for a received descriptor. */
    String path() {
        return path;
    }

    FileChannel channel() {
        return channel;
    }

    long size() throws IOException {
        return channel.size();
    }

    /** How far the stream readers got, in bytes of this file. */
    long consumed() {
        try {
            return channel.position();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Up to {@code length} bytes at {@code position}, without moving the read position. */
    byte[] readAt(long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        int total = 0;
        while (total < length) {
            int read = channel.read(buffer, position + total);
            if (read <= 0) {
                break;
            }
            total += read;
        }
        return total == length ? buffer.array() : Arrays.copyOf(buffer.array(), total);
    }

    /** A new stream from the start of the file. Closing it leaves this source open. */
    InputStream openStream() throws IOException {
        channel.position(0L);
        return new ChannelStream(channel);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private static final class ChannelStream extends InputStream {

        private final FileChannel channel;

        ChannelStream(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            return channel.read(ByteBuffer.wrap(buffer, offset, length));
        }
    }
}
