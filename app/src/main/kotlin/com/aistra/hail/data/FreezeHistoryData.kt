package com.aistra.hail.data

import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HFiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists history of auto-freeze events for display in Settings.
 */
object FreezeHistoryData {
    private const val DIR = "auto_sleep"
    private const val FILE = "freeze_history.json"
    private const val KEY_DATE = "date" // YYYY-MM-DD
    private const val KEY_PACKAGES = "packages"
    private const val KEY_COUNT = "count"
    private const val MAX_ENTRIES = 90 // ~3 months

    private val dirPath: String get() = "${app.filesDir.path}/$DIR"
    private val filePath: String get() = "$dirPath/$FILE"

    data class FreezeEvent(
        val date: String,      // "2026-07-04"
        val count: Int,
        val packages: List<String>
    )

    private var _history: MutableList<FreezeEvent>? = null

    val history: List<FreezeEvent>
        get() {
            if (_history == null) load()
            return _history ?: emptyList()
        }

    /** Record a freeze event for today */
    fun recordFreeze(packages: List<String>) {
        if (packages.isEmpty()) return
        if (_history == null) load()

        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        // Find if we already have today's entry
        val existing = _history?.indexOfFirst { it.date == today }
        if (existing != null && existing >= 0) {
            // Merge: combine packages, deduplicate, update count
            val prev = _history!![existing]
            val merged = (prev.packages + packages).distinct()
            _history!![existing] = FreezeEvent(today, merged.size, merged)
        } else {
            _history?.add(FreezeEvent(today, packages.size, packages))
        }

        // Trim to max entries
        if (_history!!.size > MAX_ENTRIES) {
            _history = _history!!.takeLast(MAX_ENTRIES).toMutableList()
        }
        save()
    }

    /** Get total freeze count (sum of all events) */
    fun getTotalFreezeCount(): Int = history.sumOf { it.count }

    /** Get events in date-descending order */
    fun getRecentHistory(limit: Int = 30): List<FreezeEvent> =
        history.sortedByDescending { it.date }.take(limit)

    /** Clear all history */
    fun clear() {
        _history = mutableListOf()
        save()
    }

    private fun load() {
        _history = mutableListOf()
        runCatching {
            if (!HFiles.exists(filePath)) return@runCatching
            val json = JSONArray(HFiles.read(filePath))
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val packages = mutableListOf<String>().apply {
                    val arr = obj.getJSONArray(KEY_PACKAGES)
                    for (j in 0 until arr.length()) add(arr.getString(j))
                }
                _history?.add(
                    FreezeEvent(
                        date = obj.getString(KEY_DATE),
                        count = obj.getInt(KEY_COUNT),
                        packages = packages
                    )
                )
            }
        }
    }

    private fun save() {
        if (!HFiles.exists(dirPath)) HFiles.createDirectories(dirPath)
        HFiles.write(filePath, JSONArray().run {
            _history?.forEach { event ->
                put(
                    JSONObject()
                        .put(KEY_DATE, event.date)
                        .put(KEY_COUNT, event.count)
                        .put(KEY_PACKAGES, JSONArray(event.packages))
                )
            }
            toString()
        })
    }
}
