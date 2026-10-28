package com.titipong.safmulticopy.data

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 = Folder pairs, 1 = Quick copy */
    var tab: Int
        get() = p.getInt("tab", 0)
        set(v) = p.edit { putInt("tab", v) }

    var sources: List<String>
        get() = p.getString("sources", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = p.edit { putString("sources", v.joinToString("\n")) }

    var files: List<String>
        get() = p.getString("files", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = p.edit { putString("files", v.joinToString("\n")) }

    var destination: String?
        get() = p.getString("destination", null)
        set(v) = p.edit { putString("destination", v) }

    var threads: Int
        get() = p.getInt("threads", 8)
        set(v) = p.edit { putInt("threads", v) }

    var retries: Int
        get() = p.getInt("retries", 2)
        set(v) = p.edit { putInt("retries", v) }

    var mirror: Boolean
        get() = p.getBoolean("mirror", false)
        set(v) = p.edit { putBoolean("mirror", v) }

    var keepScreenOn: Boolean
        get() = p.getBoolean("keepScreenOn", true)
        set(v) = p.edit { putBoolean("keepScreenOn", v) }

    var keepDates: Boolean
        get() = p.getBoolean("keepDates", true)
        set(v) = p.edit { putBoolean("keepDates", v) }

    var showSkipped: Boolean
        get() = p.getBoolean("showSkipped", false)
        set(v) = p.edit { putBoolean("showSkipped", v) }

    var excludeFiles: String
        get() = p.getString("excludeFiles", "") ?: ""
        set(v) = p.edit { putString("excludeFiles", v) }

    var excludeDirs: String
        get() = p.getString("excludeDirs", "") ?: ""
        set(v) = p.edit { putString("excludeDirs", v) }
}
