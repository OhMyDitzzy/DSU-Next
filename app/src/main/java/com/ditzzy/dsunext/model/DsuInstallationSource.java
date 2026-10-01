package com.ditzzy.dsunext.model;

import android.net.Uri;

import java.util.Collections;
import java.util.List;

/**
 * Describes what is going to be installed as a DSU. Instances are created through the
 * static factories so every {@link Type} always carries a consistent set of fields.
 */
public final class DsuInstallationSource {

    public enum Type {
        NONE, SINGLE_SYSTEM_IMAGE, DSU_PACKAGE, URL, MULTIPLE_IMAGES
    }

    private final Type type;
    private final Uri uri;
    private final long fileSize;
    private final List<ImagePartition> images;

    private DsuInstallationSource(Type type, Uri uri, long fileSize, List<ImagePartition> images) {
        this.type = type;
        this.uri = uri;
        this.fileSize = fileSize;
        this.images = images;
    }

    public static DsuInstallationSource none() {
        return new DsuInstallationSource(
                Type.NONE, Uri.EMPTY, DsuConstants.DEFAULT_IMAGE_SIZE, Collections.emptyList());
    }

    public static DsuInstallationSource singleSystemImage(Uri uri, long fileSize) {
        return new DsuInstallationSource(
                Type.SINGLE_SYSTEM_IMAGE, uri, fileSize, Collections.emptyList());
    }

    public static DsuInstallationSource dsuPackage(Uri uri) {
        return new DsuInstallationSource(
                Type.DSU_PACKAGE, uri, DsuConstants.DEFAULT_IMAGE_SIZE, Collections.emptyList());
    }

    public static DsuInstallationSource url(Uri uri) {
        return new DsuInstallationSource(
                Type.URL, uri, DsuConstants.DEFAULT_IMAGE_SIZE, Collections.emptyList());
    }

    public static DsuInstallationSource multipleImages(List<ImagePartition> images) {
        return new DsuInstallationSource(
                Type.MULTIPLE_IMAGES, Uri.EMPTY, DsuConstants.DEFAULT_IMAGE_SIZE, images);
    }

    public Type getType() {
        return type;
    }

    public Uri getUri() {
        return uri;
    }

    public long getFileSize() {
        return fileSize;
    }

    public List<ImagePartition> getImages() {
        return images;
    }

    @Override
    public String toString() {
        return "DsuInstallationSource(type=" + type + ", uri=" + uri + ", fileSize=" + fileSize + ")";
    }
}
