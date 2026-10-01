package com.ditzzy.dsunext.preparation;

import android.net.Uri;

/** A file produced (or validated) during preparation, along with the size DSU should be told. */
public final class PreparedFile {

    private final Uri uri;
    private final long size;

    public PreparedFile(Uri uri, long size) {
        this.uri = uri;
        this.size = size;
    }

    public Uri getUri() {
        return uri;
    }

    public long getSize() {
        return size;
    }
}
