package com.dshpet.android.pet

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 透明视频渲染视图。
 *
 * 素材是构建期重编码的"旁路 alpha"h264：左半 = RGB 画面，右半 = 灰度 alpha 通道
 * （Android 平台解码器无法输出 VP9 alpha，故打包成普通 h264，任意设备都能硬解）。
 * ExoPlayer 解码 → SurfaceTexture，GL shader 取左半 RGB + 右半灰度重新合成透明像素。
 *
 * 支持：水平镜像（朝向翻转）、播放速度、播放完成回调、播放进度查询（移动插值用）。
 */
class PetVideoView(context: Context) : GLSurfaceView(context) {

    /** XML 布局 inflate 需要 (Context, AttributeSet) 构造函数 */
    constructor(context: Context, attrs: android.util.AttributeSet?) : this(context)

    interface Listener {
        fun onVideoEnded(name: String)
        fun onVideoError(name: String, msg: String)
    }

    private val TAG = "PetVideoView"
    private var listener: Listener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: android.view.Surface? = null
    /**
     * 已释放标志：置位后 GL 线程不再触碰纹理/Surface。
     * 释放顺序必须为 window.removeView → onDetachedFromWindow（本类）或在服务里
     * 先 release() 再 removeView；绝不能在解码器仍在向 Surface 出帧、GL 线程仍在
     * updateTexImage 时 release() SurfaceTexture —— 那是原生竞态，可能直接打挂
     * SurfaceFlinger（表现为整机只剩画面、触摸与电源键全部无响应）。
     */
    @Volatile private var released = false
    private var textureId = 0
    private var program = 0
    private var uTexLoc = 0
    private var uMirrorLoc = 0
    private var uMLoc = 0
    private var uCosLoc = 0
    private var uSinLoc = 0
    private var uHalfLoc = 0
    private var mirror = false
    /**
     * 画面旋转角（度，顺时针）。**不用 View.rotation**：本视图是
     * `setZOrderOnTop(true)` 的 GLSurfaceView（独立 Surface 层），View 的
     * rotation/scale 变换在独立 surface 上不会被正确应用（表现为"没转"甚至被拉长）。
     * 所以在顶点着色器里按像素空间旋转四边形（见 VERTEX_SHADER）。
     */
    @Volatile private var rotDeg = 0f
    @Volatile private var rotCos = 1f
    @Volatile private var rotSin = 0f
    private val stMatrix = FloatArray(16)

    private var ready = false
    private var currentName: String? = null
    private var firstFrameRendered = false
    private var framesDrawn = 0

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        val n = currentName
                        if (n != null) mainHandler.post { listener?.onVideoEnded(n) }
                    } else if (state == Player.STATE_READY) {
                        firstFrameWatchdog()
                    }
                }

                override fun onRenderedFirstFrame() {
                    firstFrameRendered = true
                    com.dshpet.android.util.AppLog.log("VIDEO", "首帧已渲染: $currentName")
                    val rt = Runtime.getRuntime()
                    com.dshpet.android.util.AppLog.log(
                        "MEM", "首帧时: 堆已用${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB"
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    val n = currentName
                    if (n != null) mainHandler.post { listener?.onVideoError(n, error.message ?: "播放失败") }
                }
            })
        }
    }

    private val renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                onSurfaceCreatedInternal()
            } catch (e: Throwable) {
                com.dshpet.android.util.AppLog.log("GL", "onSurfaceCreated 失败: ${e.message}")
            }
        }

        private fun onSurfaceCreatedInternal() {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
            uMirrorLoc = GLES20.glGetUniformLocation(program, "uMirror")
            com.dshpet.android.util.AppLog.log(
                "GL",
                "surface created: renderer=${GLES20.glGetString(GLES20.GL_RENDERER)} " +
                        "version=${GLES20.glGetString(GLES20.GL_VERSION)} program=$program " +
                        "surface=${width}x${height}"
            )
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            uMLoc = GLES20.glGetUniformLocation(program, "uM")
            uCosLoc = GLES20.glGetUniformLocation(program, "uCos")
            uSinLoc = GLES20.glGetUniformLocation(program, "uSin")
            uHalfLoc = GLES20.glGetUniformLocation(program, "uHalf")

            val st = SurfaceTexture(textureId)
            st.setOnFrameAvailableListener { requestRender() }
            surfaceTexture = st
            surface = android.view.Surface(st)
            ready = true
            mainHandler.post {
                player.setVideoSurface(surface)
                pendingPlay?.let { doPlay(it.first, it.second, it.third); pendingPlay = null }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            framesDrawn++
            if (framesDrawn == 30) {
                // 黑屏排查：读回左下角像素。a=0 说明 GL 输出透明（问题在合成层），
                // a=255 说明帧缓冲无 alpha（EGL 配置/surface 格式问题）。
                try {
                    val bb = java.nio.ByteBuffer.allocateDirect(4)
                    GLES20.glReadPixels(2, 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
                    com.dshpet.android.util.AppLog.log(
                        "GL", "readPixels(2,2) rgba=(${bb.get(0).toInt()},${bb.get(1).toInt()},${bb.get(2).toInt()},${bb.get(3).toInt()})"
                    )
                } catch (e: Throwable) {
                    com.dshpet.android.util.AppLog.log("GL", "readPixels 失败: ${e.message}")
                }
            }
            if (framesDrawn % 150 == 0) {
                com.dshpet.android.util.AppLog.log(
                    "GL", "已渲染 $framesDrawn 帧，firstFrame=$firstFrameRendered name=$currentName"
                )
            }
            try {
                onDrawFrameInternal()
            } catch (e: Throwable) {
                // GL 线程异常会导致原生崩溃：单帧失败只清屏，不抛出
                try { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT) } catch (ignored: Throwable) {}
            }
        }

        private fun onDrawFrameInternal() {
            if (released) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                return
            }
            val st = surfaceTexture ?: run {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                return
            }
            try {
                st.updateTexImage()
            } catch (e: Exception) {
                // 首帧前 updateTexImage 在个别驱动上可能异常：跳过本帧
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                return
            }
            st.getTransformMatrix(stMatrix)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexLoc, 0)
            GLES20.glUniform1f(uMirrorLoc, if (mirror) 1f else 0f)
            GLES20.glUniformMatrix4fv(uMLoc, 1, false, stMatrix, 0)
            // 像素空间的等比旋转（视口半宽/半高）：uCos/uSin = 1/0 时即原样不旋转
            GLES20.glUniform1f(uCosLoc, rotCos)
            GLES20.glUniform1f(uSinLoc, rotSin)
            val hw = width / 2f
            val hh = height / 2f
            GLES20.glUniform2f(uHalfLoc, if (hw > 0f) hw else 1f, if (hh > 0f) hh else 1f)

            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(0)
        }
    }

    /** 等待 GL 就绪后由外部设置的待播项 */
    private var pendingPlay: Triple<String, String, Float>? = null

    init {
        setEGLContextClientVersion(2)
        // 纯 2D 全屏四边形：不需要 depth/stencil，放宽配置提高兼容性
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        // SurfaceView 的 surface 默认是 OPAQUE（无 alpha 通道），
        // 置顶合成时逐像素透明不生效 → 黑底。必须显式设半透明格式
        //（对应桌面端 WA_TranslucentBackground 的 surface 层等效）。
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setRenderer(renderer)
        // 仅在有新帧时渲染：持续 60fps 透明 GL 合成在平板 GPU 上开销极大
        // （多开时更甚），按需渲染可显著降低负载与驱动风险。
        // SurfaceTexture 的 onFrameAvailable 已调用 requestRender()。
        renderMode = RENDERMODE_WHEN_DIRTY
        setZOrderOnTop(true)
    }

    fun setListener(l: Listener) {
        listener = l
    }

    /** 播放一段动画。assetPath 形如 "idle/待机呼吸休闲.mp4"。 */
    fun play(name: String, assetPath: String, speed: Float) {
        if (!ready) {
            pendingPlay = Triple(name, assetPath, speed)
            return
        }
        doPlay(name, assetPath, speed)
    }

    private fun doPlay(name: String, assetPath: String, speed: Float) {
        currentName = name
        lastPlayed = Triple(name, assetPath, speed)
        val uri = Uri.parse("asset:///pet/videos/$assetPath")
        player.setMediaItem(MediaItem.fromUri(uri))
        player.playbackParameters = PlaybackParameters(speed)
        player.prepare()
        player.playWhenReady = true
    }

    /** 播放进度（毫秒），移动插值用 */
    fun currentPositionMs(): Long = if (player.duration > 0 && player.duration != androidx.media3.common.C.TIME_UNSET) {
        player.currentPosition
    } else 0L

    fun setMirror(m: Boolean) {
        if (mirror == m) return
        mirror = m
        requestRender()
    }

    /**
     * 画面旋转（度，顺时针）。着色器实现 → 独立 Surface 层也能真正旋转且不拉伸。
     * 0 度 = 原样。
     */
    fun setRotationDegrees(deg: Float) {
        if (rotDeg == deg) return
        rotDeg = deg
        val rad = Math.toRadians(deg.toDouble())
        rotCos = Math.cos(rad).toFloat()
        rotSin = Math.sin(rad).toFloat()
        requestRender()
    }

    fun setPlaybackSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    /** 暂停播放（隐藏时省电） */
    fun pausePlay() {
        player.playWhenReady = false
    }

    fun resumePlay() {
        if (player.mediaItemCount > 0) {
            player.playWhenReady = true
        } else {
            // 尚未有媒体：重新播放上次动画
            lastPlayed?.let { (n, p, s) -> play(n, p, s) }
        }
    }

    private var lastPlayed: Triple<String, String, Float>? = null

    /**
     * 释放播放器与纹理。顺序很关键（见 [released] 注释）：
     * 1) 置 released，GL 线程后续帧直接跳过；
     * 2) 断开 ExoPlayer 的视频输出并释放（停止向 Surface 出帧）；
     * 3) 纹理/Surface 的 release 放到 GL 线程队列执行（与 onDrawFrame 串行，
     *    避免"解码器/GL 线程正在用 → 主线程 release"的原生竞态）。
     */
    fun release() {
        released = true
        runCatching { player.setVideoSurface(null) }
        runCatching { player.release() }
        runCatching {
            queueEvent {
                runCatching { surfaceTexture?.release() }
                surfaceTexture = null
                runCatching { surface?.release() }
                surface = null
            }
        }
    }

    override fun onDetachedFromWindow() {
        released = true
        ready = false
        // 只断开解码输出，绝不在此处 release SurfaceTexture：
        // super 只是"请求"GL 线程退出（异步），此刻纹理可能仍被使用。
        runCatching { player.setVideoSurface(null) }
        super.onDetachedFromWindow()
    }

    /** 播放就绪后 5 秒仍无首帧 → 记录日志并重连 surface 重试（排查黑屏） */
    private fun firstFrameWatchdog() {
        if (firstFrameRendered) return
        mainHandler.postDelayed({
            if (!firstFrameRendered) {
                com.dshpet.android.util.AppLog.log(
                    "VIDEO", "就绪后 5s 未渲染首帧！surfaceReady=$ready name=$currentName"
                )
                // 重连 surface + 从头播放
                if (ready && currentName != null) {
                    runCatching {
                        val p = currentName
                        player.setVideoSurface(null)
                        player.setVideoSurface(surface)
                        player.pause()
                        player.seekTo(0)
                        player.playWhenReady = true
                        mainHandler.post { firstFrameWatchdog() }
                    }
                }
            }
        }, 5000)
    }

    companion object {

        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(
                    -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
                ))
                position(0)
            }

        // SurfaceTexture 输出必须绑定 GL_TEXTURE_EXTERNAL_OES 并用
        // samplerExternalOES 采样（绑定成 GL_TEXTURE_2D 会采到空纹理 → 全黑）。
        // uM 为 SurfaceTexture.getTransformMatrix() 的 UV 变换（含 Y 翻转）。
        //
        // 旋转：顶点位置先换算到"像素空间"（乘视口半宽/半高）、旋转、再换回裁剪空间。
        // 直接在裁剪空间旋转会导致非等比拉伸（视口不是正方形）；uHalf 就是用来做
        // 等比校正的。UV 仍取未旋转的 aPos，因此贴图跟着四边形一起转。
        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform mat4 uM;
            uniform float uCos;
            uniform float uSin;
            uniform vec2 uHalf;
            varying vec2 vUV;
            void main() {
                vUV = (uM * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
                vec2 p = aPos * uHalf;
                vec2 r = vec2(p.x * uCos - p.y * uSin, p.x * uSin + p.y * uCos);
                gl_Position = vec4(r / uHalf, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform float uMirror;
            varying vec2 vUV;
            void main() {
                float x = uMirror > 0.5 ? (1.0 - vUV.x) : vUV.x;
                vec4 rgb = texture2D(uTex, vec2(x * 0.5, vUV.y));
                float a = texture2D(uTex, vec2(0.5 + x * 0.5, vUV.y)).r;
                gl_FragColor = vec4(rgb.rgb, a);
            }
        """

        private fun createProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            // 显式绑定 attribute 位置 0，避免个别驱动随机分配
            GLES20.glBindAttribLocation(p, 0, "aPos")
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e("PetVideoView", "link error: " + GLES20.glGetProgramInfoLog(p))
            }
            return p
        }

        private fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val status = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e("PetVideoView", "compile error: " + GLES20.glGetShaderInfoLog(sh))
            }
            return sh
        }
    }
}
