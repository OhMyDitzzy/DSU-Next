package com.ditzzy.dsunext.model;

import android.net.Uri;

public final class ImagePartition {

    private final String partitionName;
    private final Uri uri;
    private final long fileSize;

    public ImagePartition(String partitionName, Uri uri, long fileSize) {
        this.partitionName = partitionName;
        this.uri = uri;
        this.fileSize = fileSize;
    }

    public String getPartitionName() {
        return partitionName;
    }

    public Uri getUri() {
        return uri;
    }

    public long getFileSize() {
        return fileSize;
    }
}
