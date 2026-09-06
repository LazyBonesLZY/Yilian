package com.esurfing.client.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val label: String) {
    FATAL("FATAL"),
    ERROR("ERROR"),
    WARN("WARN"),
    INFO("INFO"),
    DEBUG("DEBUG"),
    VERBOSE("VERBOSE"),
}

/** [id] 单调递增，作为 Compose lazy list 的稳定 key。 */
data class LogEntry(val id: Long, val time: String, val level: LogLevel, val message: String)

/** 内存日志环形缓冲，同时转发到 logcat 与日志文件。 */
object AppLog {

    private const val TAG = "ESurfing"
    private const val MAX_ENTRIES = 800

    /** SimpleDateFormat 不是线程安全的，按线程各持一份。 */
    private val formatter = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
    private val nextId = java.util.concurrent.atomic.AtomicLong(0)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    @Volatile
    var minLevel: LogLevel = LogLevel.INFO

    fun fatal(message: String) = log(LogLevel.FATAL, message)
    fun error(message: String) = log(LogLevel.ERROR, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun verbose(message: String) = log(LogLevel.VERBOSE, message)

    fun log(level: LogLevel, message: String) {
        if (level.ordinal > minLevel.ordinal) return
        when (level) {
            LogLevel.FATAL, LogLevel.ERROR -> Log.e(TAG, message)
            LogLevel.WARN -> Log.w(TAG, message)
            LogLevel.INFO -> Log.i(TAG, message)
            LogLevel.DEBUG -> Log.d(TAG, message)
            LogLevel.VERBOSE -> Log.v(TAG, message)
        }
        LogStore.append(level, message)
        // 引擎线程与界面线程都会写，必须用 update 做原子的读-改-写，
        // 直接给 value 赋值会在并发时丢日志
        _entries.update { current ->
            val next = current + LogEntry(nextId.incrementAndGet(), formatter.get()!!.format(Date()), level, message)
            if (next.size > MAX_ENTRIES) next.subList(next.size - MAX_ENTRIES, next.size) else next
        }
    }

    /** 清空内存缓冲，并把日志文件一起删掉。 */
    fun clear() {
        _entries.value = emptyList()
        LogStore.clear()
    }
}
