package com.aistra.hail.data

import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HFiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracks apps that were automatically frozen by the Auto Deep Sleep feature.
 * When the user launches one of these apps (detected by Xposed hook),
 * it will be unfrozen and removed from this list.
 */
object AutoSleepData {
    private const val DIR = "auto_sleep"
    private const val FILE = "auto_slept_apps.json"
    private const val KEY_PACKAGE = "package"
    private const val KEY_SLEPT_AT = "slept_at" // timestamp when auto-slept

    private val dirPath: String get() = "${app.filesDir.path}/$DIR"
    private val filePath: String get() = "$dirPath/$FILE"

    data class SleptApp(
        val packageName: String,
        val sleptAt: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put(KEY_PACKAGE, packageName)
            put(KEY_SLEPT_AT, sleptAt)
        }

        companion object {
            fun fromJson(json: JSONObject): SleptApp = SleptApp(
                packageName = json.getString(KEY_PACKAGE),
                sleptAt = json.optLong(KEY_SLEPT_AT, System.currentTimeMillis())
            )
        }
    }

    private var _autoSleptApps: MutableList<SleptApp>? = null

    /** Apps that were auto-frozen by the Auto Deep Sleep feature */
    val autoSleptApps: List<SleptApp>
        get() {
            if (_autoSleptApps == null) load()
            return _autoSleptApps ?: emptyList()
        }

    /** Package names of auto-slept apps */
    val autoSleptPackages: Set<String>
        get() = autoSleptApps.map { it.packageName }.toSet()

    /** Mark an app as auto-slept */
    fun addAutoSlept(packageName: String) {
        if (_autoSleptApps == null) load()
        if (_autoSleptApps?.any { it.packageName == packageName } == true) return
        _autoSleptApps?.add(SleptApp(packageName))
        save()
    }

    /** Remove an app from auto-slept list (e.g. when user launches it) */
    fun removeAutoSlept(packageName: String) {
        if (_autoSleptApps == null) load()
        _autoSleptApps?.removeAll { it.packageName == packageName }
        save()
    }

    /** Check if an app was auto-slept */
    fun isAutoSlept(packageName: String): Boolean =
        autoSleptPackages.contains(packageName)

    /** Get all auto-slept package names */
    fun getAutoSleptPackageNames(): Set<String> = autoSleptPackages

    private fun load() {
        _autoSleptApps = mutableListOf<SleptApp>().apply {
            runCatching {
                if (!HFiles.exists(filePath)) return@runCatching
                val json = JSONArray(HFiles.read(filePath))
                for (i in 0 until json.length()) {
                    add(SleptApp.fromJson(json.getJSONObject(i)))
                }
            }
        }
    }

    private fun save() {
        if (!HFiles.exists(dirPath)) HFiles.createDirectories(dirPath)
        HFiles.write(filePath, JSONArray().run {
            _autoSleptApps?.forEach {
                put(it.toJson())
            }
            toString()
        })
    }
}
