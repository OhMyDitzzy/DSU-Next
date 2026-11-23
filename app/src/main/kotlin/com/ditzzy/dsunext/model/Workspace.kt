package com.ditzzy.dsunext.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList

/** One entry of the workspace list. */
class Workspace {
    private val name: String

    private val ready: Boolean

    private val fsType: String

    private val imageSize: Long

    private val importedAt: Long

    private val source: String

    private val androidRelease: String

    private constructor(name: String, ready: Boolean, fsType: String, imageSize: Long, importedAt: Long, source: String, androidRelease: String) {
        this.name = name
        this.ready = ready
        this.fsType = fsType
        this.imageSize = imageSize
        this.importedAt = importedAt
        this.source = source
        this.androidRelease = androidRelease
    }

    fun getName(): String {
        return name
    }

    /** False for a workspace whose import never finished. */
    fun isReady(): Boolean {
        return ready
    }

    /** "ext4" or "erofs", empty when unknown. */
    fun getFsType(): String {
        return fsType
    }

    fun getImageSize(): Long {
        return imageSize
    }

    fun getImportedAt(): Long {
        return importedAt
    }

    fun getSource(): String {
        return source
    }

    fun getAndroidRelease(): String {
        return androidRelease
    }

    companion object {
        @JvmStatic
        @Throws(JSONException::class)
        fun parseList(json: String): List<Workspace> {
            val array: JSONArray = JSONArray(json)
            val result: MutableList<Workspace> = ArrayList<Workspace>(array.length())
            run {
                var i: Int = 0
                while ((i < array.length())) {
                    val o: JSONObject = array.getJSONObject(i)
                    result.add(Workspace(o.getString("name"), "ready".equals(o.optString("state")), o.optString("fs_type", ""), parseLong(o.optString("image_size", "0")), parseLong(o.optString("imported_at", "0")), o.optString("source", ""), o.optString("android_release", "")))
                    i++
                }
            }
            return result
        }

        @JvmStatic
        fun parseLong(value: String): Long {
            try {
                return java.lang.Long.parseLong(value)
            } catch (e: NumberFormatException) {
                return 0L
            }
        }
    }
}
