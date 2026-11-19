package com.photonx.camera.data

import com.photonx.camera.domain.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor() {

    private val maxEntries = 250
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    fun info(tag: String, message: String, durationMs: Long? = null) {
        append(LogEntry(now(), "INFO", tag, message, durationMs))
    }

    fun warn(tag: String, message: String, durationMs: Long? = null) {
        append(LogEntry(now(), "WARN", tag, message, durationMs))
    }

    fun error(tag: String, message: String, throwable: Throwable? = null, durationMs: Long? = null) {
        val fullMsg = if (throwable != null) {
            "$message | ${throwable.javaClass.simpleName}: ${throwable.message ?: "unknown error"}"
        } else {
            message
        }
        append(LogEntry(now(), "ERROR", tag, fullMsg, durationMs))
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun exportPlainText(): String {
        val current = _logs.value
        if (current.isEmpty()) return "No PhotonX logs recorded yet."
        return current.joinToString(separator = "\n") { it.formatPlainText() }
    }

    private fun append(entry: LogEntry) {
        _logs.update { existing ->
            val updated = existing + entry
            if (updated.size > maxEntries) updated.takeLast(maxEntries) else updated
        }
    }
}
