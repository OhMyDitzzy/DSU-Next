package com.ditzzy.dsunext.workspace;

import java.io.IOException;

final class ImageFormat {

    enum Container {
        EXT4, EROFS, SPARSE, ZIP, SEVEN_Z, GZIP, XZ, TAR, UNKNOWN;

        boolean isRawImage() {
            return this == EXT4 || this == EROFS;
        }
    }

    private ImageFormat() {
    }

    static Container detect(ImageSource source) throws IOException {
        byte[] head = source.readAt(0L, 8);
        if (startsWith(head, 0x3A, 0xFF, 0x26, 0xED)) {
            return Container.SPARSE;
        }
        if (startsWith(head, 'P', 'K', 0x03, 0x04)) {
            return Container.ZIP;
        }
        if (startsWith(head, 0x1F, 0x8B)) {
            return Container.GZIP;
        }
        if (startsWith(head, 0xFD, '7', 'z', 'X', 'Z', 0x00)) {
            return Container.XZ;
        }
        if (startsWith(head, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C)) {
            return Container.SEVEN_Z;
        }
        // EROFS magic 0xE0F5E1E2 at 1024, ext4 magic 0xEF53 at 1080, both little endian
        if (startsWith(source.readAt(1024L, 4), 0xE2, 0xE1, 0xF5, 0xE0)) {
            return Container.EROFS;
        }
        if (startsWith(source.readAt(1080L, 2), 0x53, 0xEF)) {
            return Container.EXT4;
        }
        if (isTar(source.readAt(257L, 5))) {
            return Container.TAR;
        }
        return Container.UNKNOWN;
    }

    /** True when the first 512 bytes of a stream are a tar header. */
    static boolean isTarHeader(byte[] head, int length) {
        if (length < 262) {
            return false;
        }
        byte[] magic = new byte[5];
        System.arraycopy(head, 257, magic, 0, 5);
        return isTar(magic);
    }

    private static boolean isTar(byte[] magic) {
        return startsWith(magic, 'u', 's', 't', 'a', 'r');
    }

    private static boolean startsWith(byte[] data, int... expected) {
        if (data.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
