package com.llmrouter

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.7.1 全局应用日志 — 调试阶段所有 Log.i/e/w 均同步写入私有日志文件
 *   /data/data/com.llmrouter/files/app_log.txt
 * 同时继续打 android.util.Log，方便 logcat 也能看到。
 *
 * 运行时刻如果想看最新日志，到 `/data/data/com.llmrouter/files/app_log.txt` 直接读取即可。
 */
object AppLogger {

    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private lateinit var logFile: File
    private lateinit var writer: FileWriter
    private lateinit var bgHandler: Handler

    private val started = AtomicBoolean(false)

    /** LlmRouterApp.onCreate 调用一次即可 */
    fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return

        // 优先写到外部存储：/sdcard/Android/data/com.llmrouter/files/app_log.txt
        // （adb shell / Shizuku 可读，用户文件管理器可见，方便调试查看）
        // 外部存储不可用（无外置/未挂载）时退回应用内部目录。
        val external = context.getExternalFilesDir(null)
        val file = if (external != null) File(external, "app_log.txt") else File(context.filesDir, "app_log.txt")

        // 超过 2 MB 就截断保留后半部分
        if (file.length() > 2_000_000) {
            val content = file.readText()
            file.writeText(content.takeLast(256_000))
        }
        logFile = file
        // v0.7.2: 文件初始化失败（外部存储不可写/IO 异常）绝不崩溃 App，
        // 降级为仅 logcat（bgHandler 不初始化即跳过落盘）。
        try {
            writer = FileWriter(file, true)

            val ht = HandlerThread("AppLogger").also { it.start() }
            bgHandler = Handler(ht.looper)
        } catch (e: Exception) {
            android.util.Log.e("AppLogger", "日志文件初始化失败，降级为仅 logcat", e)
            return
        }

        i("AppLogger", "日志系统启动, 文件=${file.absolutePath}")
    }

    fun i(tag: String, msg: String) = append('I', tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable? = null) = append('W', tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = append('E', tag, msg, tr)
    fun d(tag: String, msg: String) = append('D', tag, msg, null)

    private fun append(level: Char, tag: String, msg: String, tr: Throwable?) {
        val now = sdf.format(Date())
        val sb = StringBuilder()
        sb.append(now).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
        if (tr != null) {
            val sw = StringWriter()
            tr.printStackTrace(PrintWriter(sw))
            sb.append('\n').append(sw)
        }
        sb.append('\n')
        val line = sb.toString()

        // 同时打 logcat
        when (level) {
            'I' -> android.util.Log.i(tag, msg)
            'W' -> android.util.Log.w(tag, msg)
            'E' -> android.util.Log.e(tag, msg, tr)
            'D' -> android.util.Log.d(tag, msg)
        }

        // 异步落磁盘，避免阻塞调用方（尤其是 UI 线程）
        if (::bgHandler.isInitialized) {
            bgHandler.post {
                try {
                    writer.write(line)
                    writer.flush()
                } catch (e: Exception) {
                    // v0.7.2: 不再静默吞异常，写盘失败打 logcat 至少留痕
                    android.util.Log.e("AppLogger", "日志写盘失败", e)
                }
            }
        }
    }
}