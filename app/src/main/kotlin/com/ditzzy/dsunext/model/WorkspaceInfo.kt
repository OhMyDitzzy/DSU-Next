package com.ditzzy.dsunext.model

import org.json.JSONException
import org.json.JSONObject
import java.util.LinkedHashMap

/** The details of one workspace, as raw key/value pairs the screen formats itself. */
class WorkspaceInfo {
    private val fields: MutableMap<String, String>

    private constructor(fields: MutableMap<String, String>) {
        this.fields = fields
    }

    /** The value, or an empty string when the workspace has none. */
    fun `get`(key: String): String {
        val value: String? = fields.`get`(key)
        return (if ((value == null)) "" else value!!)
    }

    fun getLong(key: String): Long {
        return Workspace.parseLong(`get`(key))
    }

    fun has(key: String): Boolean {
        return !`get`(key).isEmpty()
    }

    companion object {
        @JvmStatic
        @Throws(JSONException::class)
        fun parse(json: String): WorkspaceInfo {
            val `object`: JSONObject = JSONObject(json)
            val fields: MutableMap<String, String> = LinkedHashMap<String, String>()
            val keys: MutableIterator<String> = `object`.keys()
            while (keys.hasNext()) {
                val key: String = keys.next()
                fields.put(key, `object`.optString(key, ""))
            }
            return WorkspaceInfo(fields)
        }
    }
}
