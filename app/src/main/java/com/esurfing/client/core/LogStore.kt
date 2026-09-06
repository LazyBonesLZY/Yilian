package com.esurfing.client.core

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 日志落盘与轮转，对应 C 版 utils/Logger.c 的 run.log + rotate()。
 *
 * 内存里那 800 条环形缓冲（[AppLog.entries]）只够回答"刚刚发生了什么"：
 * 掉线发生在半夜、或者进程被系统杀过一次，界面上就什么线索都不剩了。
 * 落到文件里才能事后回溯，也才能导出给别人看。
 *
 * 与 C 版的两处差异，都是 Android 上不得不改的：
 * - 目录用应用私有的 filesDir/logs。C 版写 /var/log/esurfing，那需要 root；
 * - 轮转文件只保留最近 [MAX_ROTATED] 份。C 版一直往目录里堆，手机上不能这么干。
 *
 * 写盘走单线程队列：拨号线程和界面线程都会记日志，直接在调用处写文件会把界面
 * 卡住（每行都要 flush，而 flush 是为了进程被杀时不丢最后几行——那几行往往
 * 正是原因所在）。
 */
object LogStore {

    private const val DIR_NAME = "logs"
    internal const val CURRENT_NAME = "run.log"
    internal const val ROTATE_SUFFIX = ".rotate.log"

    /** 与 C 版 max_lines 一致。 */
    internal const val MAX_LINES = 10_000

    /** 轮转文件保留份数。连同当前文件，最多占 4 个文件的空间。 */
    internal const val MAX_ROTATED = 3

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yilian-log").apply { isDaemon = true }
    }

    /** 只在 io 线程上用，不必考虑 SimpleDateFormat 的线程安全。 */
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 行首时间戳带日期：跨天的日志文件里，只有 HH:mm:ss 是看不出哪天的。 */
    private val lineStamp = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var dir: File? = null

    /** 用户可以关掉落盘（设置 → 日志）。关掉后已有文件保留，只是不再追加。 */
    @Volatile
    var enabled: Boolean = true

    // 下面两个只在 io 线程上访问
    private var writer: Writer? = null
    private var lines = 0

    fun init(context: Context) = init(File(context.filesDir, DIR_NAME))

    /** 直接指定目录。生产代码走上面那个重载，这个是给单测用的。 */
    internal fun init(target: File) {
        // Activity 和 Service 都会调 SettingsStore.init，重复传同一目录时直接返回
        if (dir == target) return
        runCatching { io.execute { closeWriter() } }
        dir = target
        io.execute {
            target.mkdirs()
            // 行数从现有文件续上，否则每次启动都从 0 计数，文件会涨到远超 MAX_LINES
            lines = runCatching {
                val f = File(target, CURRENT_NAME)
                if (f.exists()) f.useLines { seq -> seq.count() } else 0
            }.getOrDefault(0)
        }
    }

    fun append(level: LogLevel, message: String) {
        if (!enabled || dir == null) return
        val line = "${lineStamp.get()!!.format(Date())} [${level.label}] $message\n"
        // 队列满不了（无界队列），但执行器被关掉时 execute 会抛，不能让日志把调用方带崩
        runCatching { io.execute { write(line) } }
    }

    /** @return 日志文件总字节数，没有文件时为 0。 */
    fun sizeBytes(): Long {
        val files = dir?.listFiles() ?: return 0
        return files.filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 把全部日志按时间顺序写进 [out]（轮转文件在前，当前文件在后）。
     *
     * 会排到 io 队列尾部执行并等它完成，这样导出的内容一定包含调用之前记下的每一行。
     * 因此**不能在主线程调用**。
     *
     * @return 写出的字节数。
     */
    fun exportTo(out: OutputStream): Long {
        val target = dir ?: return 0
        return io.submit<Long> {
            flushWriter()
            var total = 0L
            for (file in orderedFiles(target)) {
                runCatching {
                    file.inputStream().use { total += it.copyTo(out) }
                }
            }
            out.flush()
            total
        }.get()
    }

    /** 删除全部日志文件（当前文件也重新开始）。 */
    fun clear() {
        val target = dir ?: return
        runCatching {
            io.execute {
                closeWriter()
                target.listFiles()?.forEach { it.delete() }
                lines = 0
            }
        }
    }

    // ---- 以下只在 io 线程上执行 ----

    /** 轮转文件名以时间戳开头，按名字排序就是按时间排序。 */
    private fun orderedFiles(target: File): List<File> {
        val all = target.listFiles()?.filter { it.isFile } ?: return emptyList()
        val rotated = all.filter { it.name.endsWith(ROTATE_SUFFIX) }.sortedBy { it.name }
        val current = all.firstOrNull { it.name == CURRENT_NAME }
        return rotated + listOfNotNull(current)
    }

    private fun write(line: String) {
        val target = dir ?: return
        try {
            // 必须是追加模式：File.bufferedWriter() 会把已有内容截掉
            val w = writer ?: FileWriter(File(target, CURRENT_NAME), true).buffered().also { writer = it }
            w.write(line)
            w.flush()
            lines++
            if (lines >= MAX_LINES) rotate(target)
        } catch (e: IOException) {
            // 磁盘满、文件被删之类。关掉落盘而不是每行都失败一次——
            // 日志系统自己不该成为日志的主要内容。
            closeWriter()
            enabled = false
            android.util.Log.e("ESurfing", "写日志文件失败, 已停止落盘: ${e.message}")
        }
    }

    private fun rotate(target: File) {
        closeWriter()
        val current = File(target, CURRENT_NAME)
        val renamed = File(target, fileStamp.format(Date()) + ROTATE_SUFFIX)
        // 改名失败（同名已存在、文件系统抽风）就直接删掉当前文件：
        // 保住"文件不会无限增长"这条底线，比保住这一份历史更重要。
        if (!current.renameTo(renamed)) current.delete()
        lines = 0
        prune(target)
    }

    private fun prune(target: File) {
        val rotated = target.listFiles()
            ?.filter { it.isFile && it.name.endsWith(ROTATE_SUFFIX) }
            ?.sortedBy { it.name }
            ?: return
        if (rotated.size <= MAX_ROTATED) return
        rotated.take(rotated.size - MAX_ROTATED).forEach { it.delete() }
    }

    /** 等写盘队列排空，仅单测用。 */
    internal fun awaitIdle() {
        runCatching { io.submit { }.get() }
    }

    private fun flushWriter() {
        runCatching { writer?.flush() }
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
    }
}
