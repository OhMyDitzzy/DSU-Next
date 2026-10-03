package com.ditzzy.dsunext.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** The details of one workspace, as raw key/value pairs the screen formats itself. */
public final class WorkspaceInfo {

    private final Map<String, String> fields;

    private WorkspaceInfo(Map<String, String> fields) {
        this.fields = fields;
    }

    public static WorkspaceInfo parse(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        Map<String, String> fields = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            fields.put(key, object.optString(key, ""));
        }
        return new WorkspaceInfo(fields);
    }

    /** The value, or an empty string when the workspace has none. */
    public String get(String key) {
        String value = fields.get(key);
        return value == null ? "" : value;
    }

    public long getLong(String key) {
        return Workspace.parseLong(get(key));
    }

    public boolean has(String key) {
        return !get(key).isEmpty();
    }
}
