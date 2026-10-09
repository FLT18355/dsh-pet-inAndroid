package com.dshpet.android.pet

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dshpet.android.util.AppLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 音乐播放器（进程级单例，所有桌宠实例共用同一个播放器）。
 *
 * - 播放列表 = `filesDir/music/` 下的音频文件（设置页「上传音乐」用 SAF 复制进来）；
 * - 播完自动下一首（REPEAT_MODE_ALL），上一首/下一首走 ExoPlayer 的 seekToXxx；
 * - 无歌词功能（按需求不做）；
 * - **懒创建**：只有用户真正点播放/切歌时才构造 ExoPlayer。打开菜单/设置页只读
 *   曲目列表，不在组合期做重活（低内存平板上这也规避了一处崩溃来源）；
 * - 播放器构造失败只降级为"无音乐"，绝不带崩进程。
 */
class PetMusicPlayer private constructor(ctx: Context) {

    /** 供 UI 渲染的状态快照（不可变） */
    data class State(
        val hasTracks: Boolean,
        val playing: Boolean,
        val title: String,
        val position: Int,
        val total: Int,
    )

    private val appCtx: Context = ctx.applicationContext
    private var tracks: List<File> = emptyList()
    /** 当前曲目下标（0 基） */
    internal var index = 0
    /** 是否已把曲目列表灌进播放器 */
    private var loaded = false
    /** 音量 0f..1f（播放器未创建时先记下，创建时套用） */
    @Volatile var volume = 0.8f
        private set
    /** 用户是否要求播放（播放器的 playWhenReady 镜像，避免为读状态而创建播放器） */
    @Volatile private var playRequested = false
    /** 已创建的播放器（null = 还没创建 / 创建失败） */
    @Volatile private var playerRef: ExoPlayer? = null

    /**
     * 删除指定曲目的文件（下标 0 基）；仅做磁盘删除，调用方需在主线程再 [reload]。
     * 返回是否删除成功。
     */
    fun deleteFileAt(i: Int): Boolean =
        runCatching { tracks.getOrNull(i)?.delete() == true }.getOrDefault(false)

    /** 曲目切换回调（服务写回 DataStore 记住下标） */
    var onIndexChanged: ((Int) -> Unit)? = null
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    private fun notifyChanged() {
        val s = state()
        listeners.forEach { runCatching { it(s) } }
    }

    /** 创建播放器（主线程；失败返回 null 并记日志） */
    private fun createPlayer(): ExoPlayer? {
        playerRef?.let { return it }
        val p = runCatching {
            ExoPlayer.Builder(appCtx).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                volume = this@PetMusicPlayer.volume
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = notifyChanged()
                    override fun onPlaybackStateChanged(playbackState: Int) = notifyChanged()
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        index = currentMediaItemIndex.coerceAtLeast(0)
                        onIndexChanged?.invoke(index)
                        notifyChanged()
                    }
                })
            }
        }.getOrElse { e ->
            AppLog.log("MUSIC", "播放器创建失败: ${e.message}")
            null
        }
        playerRef = p
        return p
    }

    // ================================================================ 播放列表
    /** 重新扫描 filesDir/music/（上传/删除音乐后调用）；不创建播放器 */
    fun reload() {
        loaded = true
        val wasPlaying = playRequested
        // 目录为应用私有且只由「上传音乐」写入，故不做扩展名过滤
        //（用户选中的音频不带扩展名时也不会被漏掉）
        tracks = dir(appCtx).listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        index = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        val p = playerRef
        if (p != null) {
            val items = tracks.map { MediaItem.fromUri(Uri.fromFile(it)) }
            runCatching {
                if (items.isEmpty()) {
                    p.clearMediaItems()
                    playRequested = false
                } else {
                    p.setMediaItems(items, index.coerceAtMost(items.size - 1), 0L)
                    p.prepare()
                    // 导入/删除音乐后不打断正在播放的状态
                    if (wasPlaying) p.playWhenReady = true
                }
            }.onFailure { AppLog.log("MUSIC", "重载播放列表失败: ${it.message}") }
        }
        p?.volume = volume
        notifyChanged()
    }

    /** 需要时创建播放器并灌入列表（只在用户操作路径调用） */
    fun ensureLoaded() {
        if (playerRef == null) createPlayer()
        if (!loaded || playerRef?.mediaItemCount != tracks.size) reload()
    }

    fun hasTracks(): Boolean = tracks.isNotEmpty()

    /** 当前曲目文件名（去扩展名），无曲目返回提示文案 */
    fun title(): String =
        tracks.getOrNull(index)?.nameWithoutExtension ?: "（还没有上传音乐）"

    /** 播放列表曲目名（去扩展名，顺序与播放顺序一致） */
    fun trackNames(): List<String> = tracks.map { it.nameWithoutExtension }

    fun state(): State = State(
        hasTracks = tracks.isNotEmpty(),
        playing = isPlaying(),
        title = title(),
        position = if (tracks.isEmpty()) 0 else (index + 1).coerceAtMost(tracks.size),
        total = tracks.size,
    )

    // ================================================================ 控制
    /** 是否正在播放（不创建播放器：未请求播放时直接 false） */
    fun isPlaying(): Boolean {
        if (!playRequested) return false
        val p = playerRef ?: return false
        return p.mediaItemCount > 0 && p.playbackState != Player.STATE_ENDED
    }

    fun play() {
        ensureLoaded()
        val p = playerRef
        if (p == null || !hasTracks()) {
            notifyChanged()
            return
        }
        playRequested = true
        runCatching { p.playWhenReady = true }
        notifyChanged()
    }

    fun pause() {
        playRequested = false
        playerRef?.let { runCatching { it.playWhenReady = false } }
        notifyChanged()
    }

    fun toggle() {
        if (isPlaying()) pause() else play()
    }

    /** 下一首（循环播放列表） */
    fun next() {
        ensureLoaded()
        val p = playerRef
        if (p == null || !hasTracks()) {
            notifyChanged()
            return
        }
        runCatching {
            if (p.hasNextMediaItem()) p.seekToNextMediaItem() else p.seekTo(0, 0L)
            index = p.currentMediaItemIndex.coerceAtLeast(0)
            onIndexChanged?.invoke(index)
            playRequested = true
            p.playWhenReady = true
        }.onFailure { AppLog.log("MUSIC", "下一首失败: ${it.message}") }
        notifyChanged()
    }

    /** 上一首（循环播放列表） */
    fun prev() {
        ensureLoaded()
        val p = playerRef
        if (p == null || !hasTracks()) {
            notifyChanged()
            return
        }
        runCatching {
            if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem() else p.seekTo(tracks.size - 1, 0L)
            index = p.currentMediaItemIndex.coerceAtLeast(0)
            onIndexChanged?.invoke(index)
            playRequested = true
            p.playWhenReady = true
        }.onFailure { AppLog.log("MUSIC", "上一首失败: ${it.message}") }
        notifyChanged()
    }

    /** 指定曲目播放（下标 0 基） */
    fun playAt(i: Int) {
        ensureLoaded()
        val p = playerRef
        if (p == null || !hasTracks()) {
            notifyChanged()
            return
        }
        runCatching {
            index = i.coerceIn(0, tracks.size - 1)
            p.seekTo(index, 0L)
            playRequested = true
            p.playWhenReady = true
            onIndexChanged?.invoke(index)
        }.onFailure { AppLog.log("MUSIC", "选曲失败: ${it.message}") }
        notifyChanged()
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        playerRef?.let { runCatching { it.volume = volume } }
        notifyChanged()
    }

    // ================================================================ 监听
    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    companion object {
        @Volatile
        private var inst: PetMusicPlayer? = null

        fun get(ctx: Context): PetMusicPlayer =
            inst ?: synchronized(this) {
                // 首次取用时扫描一次曲目列表（仅目录列举，不创建播放器）
                inst ?: PetMusicPlayer(ctx).also { it.reload(); inst = it }
            }

        /** 音乐目录（用户上传的音乐存放于此，随应用卸载删除） */
        fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, "music").apply { mkdirs() }

        /** 文件名冲突时追加序号，返回实际落盘文件 */
        fun uniqueTarget(ctx: Context, name: String): File {
            val base = name.substringBeforeLast('.', name).take(80).ifBlank { "music" }
            val ext = name.substringAfterLast('.', "").lowercase()
            var f = File(dir(ctx), if (ext.isEmpty()) base else "$base.$ext")
            var n = 1
            while (f.exists()) {
                f = File(dir(ctx), if (ext.isEmpty()) "$base($n)" else "$base($n).$ext")
                n++
            }
            return f
        }
    }
}