package com.titipong.safmulticopy.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.titipong.safmulticopy.engine.TaskResult
import com.titipong.safmulticopy.util.resultSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A saved source → destination with its own options. */
data class FolderPair(
    val id: String,
    val name: String,
    val source: String,
    val sourceName: String,
    val sourceLocation: String,
    val destination: String,
    val destName: String,
    val destLocation: String,
    val mirror: Boolean = false,
    val excludeFiles: String = "",
    val excludeDirs: String = "",
    val selected: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastStatus: String = "",
    val lastSummary: String = "",
) {
    val sourceUri: Uri get() = Uri.parse(source)
    val destUri: Uri get() = Uri.parse(destination)
}

/** Folder pairs, kept as JSON in SharedPreferences and shared by the UI and the service. */
class PairStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("pairs", Context.MODE_PRIVATE)
    private val _pairs = MutableStateFlow(load())
    val pairs: StateFlow<List<FolderPair>> = _pairs.asStateFlow()

    fun upsert(p: FolderPair) = update { list ->
        if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    fun setSelected(id: String, selected: Boolean) = update { list ->
        list.map { if (it.id == id) it.copy(selected = selected) else it }
    }

    fun setAllSelected(selected: Boolean) = update { list -> list.map { it.copy(selected = selected) } }

    fun move(id: String, delta: Int) = update { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) list
        else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun recordResults(results: List<TaskResult>, finishedAt: Long) = update { list ->
        list.map { p ->
            val r = results.lastOrNull { it.id == p.id } ?: return@map p
            p.copy(lastSyncAt = finishedAt, lastStatus = r.status.name, lastSummary = resultSummary(r, false))
        }
    }

    @Synchronized
    private fun update(change: (List<FolderPair>) -> List<FolderPair>) {
        val next = change(_pairs.value)
        _pairs.value = next
        prefs.edit { putString(KEY, encode(next)) }
    }

    private fun load(): List<FolderPair> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FolderPair(
                id = o.getString("id"),
                name = o.optString("name"),
                source = o.getString("source"),
                sourceName = o.optString("sourceName"),
                sourceLocation = o.optString("sourceLocation"),
                destination = o.getString("destination"),
                destName = o.optString("destName"),
                destLocation = o.optString("destLocation"),
                mirror = o.optBoolean("mirror", false),
                excludeFiles = o.optString("excludeFiles"),
                excludeDirs = o.optString("excludeDirs"),
                selected = o.optBoolean("selected", true),
                lastSyncAt = o.optLong("lastSyncAt", 0),
                lastStatus = o.optString("lastStatus"),
                lastSummary = o.optString("lastSummary"),
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(list: List<FolderPair>): String {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("source", p.source)
                    .put("sourceName", p.sourceName)
                    .put("sourceLocation", p.sourceLocation)
                    .put("destination", p.destination)
                    .put("destName", p.destName)
                    .put("destLocation", p.destLocation)
                    .put("mirror", p.mirror)
                    .put("excludeFiles", p.excludeFiles)
                    .put("excludeDirs", p.excludeDirs)
                    .put("selected", p.selected)
                    .put("lastSyncAt", p.lastSyncAt)
                    .put("lastStatus", p.lastStatus)
                    .put("lastSummary", p.lastSummary),
            )
        }
        return arr.toString()
    }

    companion object {
        private const val KEY = "pairs"

        @Volatile private var instance: PairStore? = null

        fun get(context: Context): PairStore =
            instance ?: synchronized(this) {
                instance ?: PairStore(context.applicationContext).also { instance = it }
            }
    }
}
