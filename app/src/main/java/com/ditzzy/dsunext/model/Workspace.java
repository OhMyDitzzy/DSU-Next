package com.ditzzy.dsunext.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One entry of the workspace list. */
public final class Workspace {

    private final String name;
    private final boolean ready;
    private final String fsType;
    private final long imageSize;
    private final long importedAt;
    private final String source;
    private final String androidRelease;

    private Workspace(String name, boolean ready, String fsType, long imageSize, long importedAt,
            String source, String androidRelease) {
        this.name = name;
        this.ready = ready;
        this.fsType = fsType;
        this.imageSize = imageSize;
        this.importedAt = importedAt;
        this.source = source;
        this.androidRelease = androidRelease;
    }

    public static List<Workspace> parseList(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<Workspace> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            result.add(new Workspace(
                    o.getString("name"),
                    "ready".equals(o.optString("state")),
                    o.optString("fs_type", ""),
                    parseLong(o.optString("image_size", "0")),
                    parseLong(o.optString("imported_at", "0")),
                    o.optString("source", ""),
                    o.optString("android_release", "")));
        }
        return result;
    }

    static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public String getName() {
        return name;
    }

    /** False for a workspace whose import never finished. */
    public boolean isReady() {
        return ready;
    }

    /** "ext4" or "erofs", empty when unknown. */
    public String getFsType() {
        return fsType;
    }

    public long getImageSize() {
        return imageSize;
    }

    public long getImportedAt() {
        return importedAt;
    }

    public String getSource() {
        return source;
    }

    public String getAndroidRelease() {
        return androidRelease;
    }
}
