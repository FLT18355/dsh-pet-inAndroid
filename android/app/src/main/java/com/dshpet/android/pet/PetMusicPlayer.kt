package com.dshpet.android.pet

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 音乐播放器（进程级单例，所有桌宠实例共用同一个播放器）。
 *
 * - 播放列表 = `filesDir/music/` 下的音频文件（设置页「上传音乐」用 SAF 复制进来）；
 * - 播完自动下一首（REPEAT_MODE_ALL），上一首/下一首走 ExoPlayer 的 seekToXxx；
 * - 无歌词功能（按需求不做）；
 * - 播放状态变化通过 [addListener] 通知（服务据此让桌宠一直播「悠闲哼歌」）。
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
    /** 是否已初始化（多实例共享时只初始化一次，避免打断正在播放的曲目） */
    private var loaded = false
    /** 音量 0f..1f */
    @Volatile var volume = 0.8f
        private set
    /**
     * 删除指定曲目的文件（下标 0 基）；仅做磁盘删除，调用方需在主线程再 [reload]。
     * 返回是否删除成功。
     */
    fun deleteFileAt(i: Int): Boolean =
        runCatching { tracks.getOrNull(i)?.delete() == true }.getOrDefault(false)

    /** 曲目切换回调（服务写回 DataStore 记住下标） */
    var onIndexChanged: ((Int) -> Unit)? = null
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    /** 播放器构造完成的标志：构造期的同步回调不派发（此时状态字段可能尚未就绪） */
    @Volatile
    private var readyForNotify = false

    // ExoPlayer 必须在主线程创建与访问（服务/菜单/设置页均在主线程调用）
    private val player: ExoPlayer = ExoPlayer.Builder(appCtx).build().apply {
        repeatMode = Player.REPEAT_MODE_ALL
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = notifyChanged()
            override fun onPlaybackStateChanged(playbackState: Int) = notifyChanged()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = currentMediaItemIndex.coerceAtLeast(0)
                onIndexChanged?.invoke(index)
                notifyChanged()
            }
        })
        readyForNotify = true
    }

    private fun notifyChanged() {
        if (!readyForNotify) return
        val s = state()
        listeners.forEach { runCatching { it(s) } }
    }

    // ================================================================ 播放列表
    /** 重新扫描 filesDir/music/（上传/删除音乐后调用） */
    fun reload() {
        loaded = true
        val wasPlaying = isPlaying()
        // 目录为应用私有且只由「上传音乐」写入，故不做扩展名过滤
        //（用户选中的音频不带扩展名时也不会被漏掉）
        tracks = dir(appCtx).listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        index = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        val items = tracks.map { MediaItem.fromUri(Uri.fromFile(it)) }
        if (items.isEmpty()) {
            player.clearMediaItems()
        } else {
            player.setMediaItems(items, index.coerceAtMost(items.size - 1), 0L)
            player.prepare()
        }
        applyVolume()
        // 导入/删除音乐后不打断正在播放的状态
        if (wasPlaying && items.isNotEmpty()) player.playWhenReady = true
        notifyChanged()
    }

    /** 首次使用时加载一次（多实例共享时后面的实例不再重置播放位置） */
    fun ensureLoaded() {
        // 未加载过，或播放器里的条目与目录扫描结果不一致（seekTo 越界会抛异常）
        if (!loaded || player.mediaItemCount != tracks.size) reload()
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
    fun isPlaying(): Boolean =
        player.playWhenReady && player.mediaItemCount > 0 && player.playbackState != Player.STATE_ENDED

    fun play() {
        ensureLoaded()
        if (!hasTracks()) return
        player.playWhenReady = true
        notifyChanged()
    }

    fun pause() {
        player.playWhenReady = false
        notifyChanged()
    }

    fun toggle() {
        if (isPlaying()) pause() else play()
    }

    /** 下一首（循环播放列表） */
    fun next() {
        ensureLoaded()
        if (!hasTracks()) return
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0, 0L)
        index = player.currentMediaItemIndex.coerceAtLeast(0)
        onIndexChanged?.invoke(index)
        player.playWhenReady = true
        notifyChanged()
    }

    /** 上一首（循环播放列表） */
    fun prev() {
        ensureLoaded()
        if (!hasTracks()) return
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(tracks.size - 1, 0L)
        index = player.currentMediaItemIndex.coerceAtLeast(0)
        onIndexChanged?.invoke(index)
        player.playWhenReady = true
        notifyChanged()
    }

    /** 指定曲目播放（下标 0 基） */
    fun playAt(i: Int) {
        ensureLoaded()
        if (tracks.isEmpty()) return
        index = i.coerceIn(0, tracks.size - 1)
        player.seekTo(index, 0L)
        player.playWhenReady = true
        onIndexChanged?.invoke(index)
        notifyChanged()
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolume()
        notifyChanged()
    }

    private fun applyVolume() {
        player.volume = volume
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
                inst ?: PetMusicPlayer(ctx).also { inst = it }
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