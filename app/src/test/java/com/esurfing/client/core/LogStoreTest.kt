package com.esurfing.client.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 日志落盘与轮转的回归测试。
 *
 * 轮转和清理坏了不会报错，只会在几个月后表现为应用占了几百 MB，
 * 或者导出的日志只剩最后几行，所以用临时目录真实跑一遍。
 */
class LogStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = folder.newFolder("logs")
        LogStore.enabled = true
        LogStore.init(dir)
        LogStore.awaitIdle()
    }

    @After
    fun tearDown() {
        LogStore.clear()
        LogStore.awaitIdle()
    }

    private fun current() = File(dir, LogStore.CURRENT_NAME)

    private fun rotated() = dir.listFiles()!!
        .filter { it.name.endsWith(LogStore.ROTATE_SUFFIX) }
        .sortedBy { it.name }

    private fun append(count: Int, prefix: String = "line") {
        repeat(count) { LogStore.append(LogLevel.INFO, "$prefix-$it") }
        LogStore.awaitIdle()
    }

    @Test
    fun writesLevelAndMessage() {
        LogStore.append(LogLevel.WARN, "心跳失败")
        LogStore.awaitIdle()
        val text = current().readText()
        assertTrue("应包含等级: $text", text.contains("[WARN]"))
        assertTrue("应包含正文: $text", text.contains("心跳失败"))
        assertEquals("一条日志就是一行", 1, text.trim().lines().size)
    }

    /** 关掉落盘后不能再写文件，否则开关形同虚设。 */
    @Test
    fun respectsDisabledFlag() {
        LogStore.enabled = false
        append(5)
        assertTrue("关掉后不应写入", !current().exists() || current().length() == 0L)
        LogStore.enabled = true
        append(1)
        assertTrue("重新打开后应能写入", current().length() > 0)
    }

    /** 追加而不是截断：这一条坏了，日志每次重启都从空开始。 */
    @Test
    fun appendsAcrossReopen() {
        append(3, "first")
        // 切到另一个目录再切回来，相当于进程重启后重新打开同一个文件
        LogStore.init(folder.newFolder("other"))
        LogStore.awaitIdle()
        LogStore.init(dir)
        append(2, "second")
        val text = current().readText()
        assertTrue("旧内容应保留: $text", text.contains("first-0"))
        assertTrue("新内容应追加: $text", text.contains("second-0"))
        assertEquals(5, text.trim().lines().size)
    }

    @Test
    fun rotatesAtMaxLines() {
        append(LogStore.MAX_LINES - 1)
        assertEquals("还差一行时不该轮转", 0, rotated().size)

        append(1, "tail")
        assertEquals("满 MAX_LINES 应轮转一次", 1, rotated().size)
        assertEquals(
            "轮转后当前文件应从空开始",
            0L,
            if (current().exists()) current().length() else 0L,
        )
        assertEquals(
            "轮转文件应装下全部 MAX_LINES 行",
            LogStore.MAX_LINES,
            rotated()[0].readText().trim().lines().size,
        )

        append(1, "after")
        assertTrue("轮转后应继续写当前文件", current().readText().contains("after-0"))
    }

    /** 轮转文件只能留 MAX_ROTATED 份——C 版一直往目录里堆，手机上不行。 */
    @Test
    fun prunesOldRotatedFiles() {
        // 预先摆好比保留上限更多的旧轮转文件；文件名就是时间戳，排序即时间序
        val stale = (1..LogStore.MAX_ROTATED + 2).map { i ->
            File(dir, "2026010$i-000000${LogStore.ROTATE_SUFFIX}")
                .apply { writeText("stale-$i\n") }
        }
        append(LogStore.MAX_LINES)

        val left = rotated()
        assertEquals("应修剪到保留上限", LogStore.MAX_ROTATED, left.size)
        assertTrue("删的应该是最旧的", !stale[0].exists() && !stale[1].exists())
        assertTrue("刚轮转出来的那份必须还在", left.last().readText().contains("line-0"))
    }

    /** 导出要把轮转与当前文件按时间拼全，而不是只给最后一段。 */
    @Test
    fun exportConcatenatesOldestFirst() {
        File(dir, "20260101-000000${LogStore.ROTATE_SUFFIX}").writeText("oldest\n")
        File(dir, "20260102-000000${LogStore.ROTATE_SUFFIX}").writeText("middle\n")
        append(1, "newest")

        val out = ByteArrayOutputStream()
        val bytes = LogStore.exportTo(out)
        val text = out.toString("UTF-8")

        assertEquals("返回值应等于写出字节数", text.toByteArray().size.toLong(), bytes)
        val order = listOf("oldest", "middle", "newest-0").map { text.indexOf(it) }
        assertTrue("三段都要在: $text", order.none { it < 0 })
        assertEquals("顺序应为旧 到 新", order.sorted(), order)
    }

    /** 清空要真的把文件删掉，否则用户以为清了其实还在盘上。 */
    @Test
    fun clearRemovesFiles() {
        File(dir, "20260101-000000${LogStore.ROTATE_SUFFIX}").writeText("old\n")
        append(3)
        assertTrue(LogStore.sizeBytes() > 0)

        LogStore.clear()
        LogStore.awaitIdle()
        assertEquals("目录应为空", 0, dir.listFiles()!!.size)
        assertEquals(0L, LogStore.sizeBytes())

        append(1, "again")
        assertTrue("清空后应能继续记", current().readText().contains("again-0"))
    }
}
