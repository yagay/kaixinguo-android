package com.kadaliao.kaixinguo.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import com.kadaliao.kaixinguo.KaixinguoApplication
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * App-owned, bounded diagnostic events. All events are explicitly typed with
 * constant labels: no URLs, account IDs, credentials, titles or request bodies.
 *
 * Optionally append the current app process's logcat. That is opt-in because
 * SDK output outside of this ring buffer may contain sensitive information.
 */
object Diagnostics {
    private data class Entry(val at: String, val area: String, val action: String)
    private val entries = ArrayDeque<Entry>()
    private const val LIMIT = 500
    private val allowed = Regex("[a-z0-9_]{1,48}")
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kxg-diagnostics").apply { isDaemon = true }
    }
    private var journal: File? = null
    private val fileLock = Any()
    private var crashHandlerInstalled = false

    @Synchronized
    fun install(context: Context) {
        journal = File(context.noBackupFilesDir, "kxg-diagnostics-events.log")
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val earlier = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            // Never persist Throwable.message: upstream errors may contain secrets.
            val header = Instant.now().toString() + " [crash] " +
                failure.javaClass.name + "\n"
            val frames = failure.stackTrace.take(45).joinToString("\n") { "  at " + it.toString() }
            synchronized(fileLock) {
                runCatching { journal?.appendText(header + frames + "\n") }
            }
            earlier?.uncaughtException(thread, failure)
        }
    }

    @Synchronized
    fun record(area: String, action: String) {
        if (!allowed.matches(area) || !allowed.matches(action)) return
        while (entries.size >= LIMIT) entries.removeFirst()
        val entry = Entry(Instant.now().toString(), area, action)
        entries.addLast(entry)
        val path = journal
        if (path != null) writer.execute {
            synchronized(fileLock) {
                runCatching {
                    if (path.length() > 160 * 1024) {
                        val last = path.readText().takeLast(64 * 1024).substringAfter('\n', "")
                        path.writeText(last)
                    }
                    path.appendText(entry.at + " [" + entry.area + "] " + entry.action + "\n")
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        val path = journal
        if (path != null) writer.execute {
            synchronized(fileLock) { runCatching { path.delete() } }
        }
        record("diagnostics", "cleared")
    }

    /**
     * Take this snapshot on the main thread, before starting document IO.
     * The account state and player state are summaries, never request data.
     */
    fun snapshot(context: Context, app: KaixinguoApplication): String {
        val model = app.model
        val router = app.router
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val events: List<Entry> = synchronized(this) { entries.toList() }
        return buildString {
            appendLine("Kaixinguo diagnostic report")
            appendLine("generated=" + Instant.now())
            appendLine("app_version=" + (info?.versionName ?: "unknown"))
            appendLine("android_sdk=" + Build.VERSION.SDK_INT)
            appendLine("device=" + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("tab=" + router.tab.name)
            appendLine("route=" + (router.top?.javaClass?.simpleName ?: "tab_root"))
            appendLine("account_phase=" + app.account.state.value.phase.name)
            appendLine("account_saved=" + app.account.state.value.hasSavedSession)
            appendLine("resume_on_launch=" + app.settings.resumeOnLaunch)
            appendLine("automatic_pip=" + app.settings.automaticPictureInPicture)
            appendLine("catalog_ready=" + (model != null))
            if (model != null) {
                appendLine("feed_home_cards=" + model.home.cards.size)
                appendLine("feed_home_loading=" + model.home.loading)
                val player = model.player
                appendLine("player_presented=" + player.isPresented)
                appendLine("player_state=" + player.state.javaClass.simpleName)
                appendLine("player_paused=" + player.paused)
                appendLine("player_episode=" + player.number)
                appendLine("player_pip=" + player.inPictureInPicture)
                appendLine("local_favorites_count=" + model.favorites.list.size)
                appendLine("local_history_count=" + model.history.list.size)
            }
            appendLine()
            appendLine("events_total=" + events.size)
            events.forEach { appendLine(it.at + " [" + it.area + "] " + it.action) }
            appendLine()
            appendLine("Privacy: no cookies, identifiers, phone numbers, series titles, or raw HTTP bodies are collected above.")
        }
    }

    fun writeReport(
        context: Context,
        uri: Uri,
        snapshot: String,
        includeProcessLogcat: Boolean,
    ) {
        val logcat = if (includeProcessLogcat) ownProcessLogcat() else ""
        val previous = synchronized(fileLock) {
            runCatching { journal?.takeIf { it.exists() }?.readText().orEmpty() }
                .getOrDefault("")
        }
        val writer = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Unable to write diagnostic document")
        writer.bufferedWriter(Charsets.UTF_8).use {
            it.write(snapshot)
            it.write("\n\n=== Persisted previous-session events (up to 160 KiB) ===\n")
            it.write(previous)
            if (includeProcessLogcat) {
                it.write("\n\n=== Optional current-process logcat (best-effort redaction) ===\n")
                it.write(logcat)
                it.write("\n\nReview the file before sharing. Logcat may contain private data.\n")
            }
        }
        record("diagnostics", "exported")
    }

    private fun ownProcessLogcat(): String {
        val process = try {
            Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "--pid=" + Process.myPid(), "-t", "1000"),
            )
        } catch (_: Exception) {
            return "Logcat is unavailable on this device.\n"
        }
        return try {
            val out = ByteArrayOutputStream()
            process.inputStream.use { input ->
                val bytes = ByteArray(4096)
                while (out.size() < 192 * 1024) {
                    val n = input.read(bytes, 0, minOf(bytes.size, 192 * 1024 - out.size()))
                    if (n <= 0) break
                    out.write(bytes, 0, n)
                }
            }
            scrub(out.toString(Charsets.UTF_8.name()))
        } catch (_: Exception) {
            "Reading current-process Logcat failed.\n"
        } finally {
            process.destroy()
        }
    }

    private fun scrub(text: String): String {
        var result = text
        result = Regex(
            "(?i)(cookie|authorization|sessionid_ss|sessionid|sid_tt|uid_tt|x-tt-token|token|mobile_ticket)" +
                "(\\s*[:=]\\s*)([^\\s;,]+)",
        ).replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + "[REDACTED]"
        }
        result = Regex("https?://\\S+").replace(result, "[URL]")
        result = Regex("(?<!\\d)\\+?\\d{9,}(?!\\d)").replace(result, "[NUMBER]")
        return result
    }
}
