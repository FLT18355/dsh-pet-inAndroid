package com.dshpet.android.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 清除缓存：应用缓存目录（解码器/缩略图/临时文件等） + 历史日志。
 *
 * 不动的东西：设置（DataStore）、已上传的音乐（filesDir/music）、自定义字体、
 * 桌宠位置状态 —— 这些是"数据"不是"缓存"。
 * 今天当天的日志保留（方便继续排查问题），只删更早的。
 */
object CacheCleaner {

    /** 清理结果：释放字节数与简要说明 */
    data class Result(val freedBytes: Long, val cacheBytes: Long, val logBytes: Long)

    fun clear(ctx: Context): Result {
        val cacheBytes = deleteContents(ctx.cacheDir)
        // 外置缓存（应用专属，卸载/清理时会被系统删）
        val extCache = runCatching { ctx.externalCacheDir }.getOrNull()
        val extBytes = if (extCache != null && extCache.absolutePath != ctx.cacheDir?.absolutePath) {
            deleteContents(extCache)
        } else 0L
        val logBytes = deleteOldLogs()
        return Result(cacheBytes + extBytes, cacheBytes + extBytes, logBytes)
    }

    /** 估算当前缓存占用（用于界面显示） */
    fun size(ctx: Context): Long {
        var n = sizeOf(ctx.cacheDir)
        val ext = runCatching { ctx.externalCacheDir }.getOrNull()
        if (ext != null && ext.absolutePath != ctx.cacheDir?.absolutePath) n += sizeOf(ext)
        return n + AppLog.files().sumOf { it.length() }
    }

    /** 删除目录内容（保留目录本身），返回释放的字节数 */
    private fun deleteContents(dir: File?): Long {
        if (dir == null || !dir.isDirectory) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            val size = sizeOf(f)
            val ok = runCatching { f.deleteRecursively() }.getOrDefault(false)
            if (ok) freed += size
        }
        return freed
    }

    /** 删除今天以前的日志；今天的留着（排查问题用） */
    private fun deleteOldLogs(): Long {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var freed = 0L
        AppLog.files().forEach { f ->
            if (f.name != "$today.log") {
                val size = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) freed += size
            }
        }
        return freed
    }

    private fun sizeOf(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return max(0L, f.length())
        var n = 0L
        f.listFiles()?.forEach { n += sizeOf(it) }
        return n
    }

    /** 人类可读大小 */
    fun fmt(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}