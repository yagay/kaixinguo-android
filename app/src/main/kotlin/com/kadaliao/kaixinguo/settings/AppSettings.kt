package com.kadaliao.kaixinguo.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kadaliao.kaixinguo.diagnostics.Diagnostics

/** User preferences only; authenticated session data is never kept here. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kaixinguo_settings_v1", Context.MODE_PRIVATE)

    var resumeOnLaunch by mutableStateOf(prefs.getBoolean("resume_on_launch", true))
        private set
    var automaticPictureInPicture by mutableStateOf(prefs.getBoolean("automatic_pip", true))
        private set
    var includeProcessLogcat by mutableStateOf(false)
        private set

    fun setResumeOnLaunch(value: Boolean) {
        prefs.edit().putBoolean("resume_on_launch", value).apply()
        resumeOnLaunch = value
        Diagnostics.record("settings", "resume_on_launch_changed")
    }

    fun setAutomaticPictureInPicture(value: Boolean) {
        prefs.edit().putBoolean("automatic_pip", value).apply()
        automaticPictureInPicture = value
        Diagnostics.record("settings", "automatic_pip_changed")
    }

    /** Intentionally not persisted: every potentially sensitive logcat export needs fresh consent. */
    fun setIncludeProcessLogcat(value: Boolean) {
        includeProcessLogcat = value
    }
}
