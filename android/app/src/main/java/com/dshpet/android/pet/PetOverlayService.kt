package com.dshpet.android.pet

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.media.SoundPool
import androidx.core.app.NotificationCompat
import com.dshpet.android.MainActivity
import com.dshpet.android.R
import com.dshpet.android.chat.ChatActivity
import com.dshpet.android.data.PetConfig
import com.dshpet.android.data.applyHideRecentsPolicy
import com.dshpet.android.data.PetState
import com.dshpet.android.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 桌宠悬浮窗前台服务（每只桌宠一个实例，多开互不干扰）。
 *
 * - TYPE_APPLICATION_OVERLAY 透明窗口，显示在任意应用之上；
 * - 手势：点击 = 点击回应动画 + Q 弹；拖动 = 跟手移动（可物理抛掷）；
 *   长按 = MD3 菜单；"仅长按可拖动"模式下长按后拖动才生效；
 * - 动画链、位置持久化（按屏幕比例换算 dp）、多实例隔离；
 * - 前台通知提供：显示/隐藏、AI 对话、设置、退出。
 */
open class PetOverlayService : Service() {

    /** 本服务类负责的实例号（系统重启 null intent 时的默认值） */
    protected open val defaultInstanceId: Int get() = 0

    companion object {
        const val CHANNEL_ID = "pet"
        const val EXTRA_INSTANCE = "instance_id"
        private const val TAG = "PetOverlay"

        /** 服务子类硬上限（0..9 共 10 个实例槽位；设置里可配 0=不限） */
        const val SLOT_COUNT = 10

        /** 音乐播放时桌宠固定播放的动画名（素材缺失则回退正常动画链） */
        const val HUMMING_ANIM = "悠闲哼歌"

        // ---- 边缘探头 ----
        /** 露出宽度（占窗口比例）：一半 */
        const val PEEK_VIS_W = 0.50f
        /** 探头斜角（度）：贴左缘头朝屏内右倾，贴右缘朝左倾 */
        const val PEEK_TILT = 45f
        /** 角色头部中心在画布上的纵向位置（用于补偿旋转带来的高度位移） */
        const val PEEK_HEAD_CY = 0.31f

        /** 进程级多开上限缓存（设置可调；0 = 无限制；PetApp 启动时同步） */
        @Volatile
        var maxInstances: Int = 4

        /** 桌宠可见性（灵动岛单击切换） */
        @Volatile
        var petHidden = false

        /** 实例号 → 服务类（Android 同一 Service 类只有一个对象，
         *  多开必须用不同服务类隔离窗口/引擎状态） */
        fun serviceClassFor(id: Int): Class<*> = when (id) {
            1 -> PetOverlayService1::class.java
            2 -> PetOverlayService2::class.java
            3 -> PetOverlayService3::class.java
            4 -> PetOverlayService4::class.java
            5 -> PetOverlayService5::class.java
            6 -> PetOverlayService6::class.java
            7 -> PetOverlayService7::class.java
            8 -> PetOverlayService8::class.java
            9 -> PetOverlayService9::class.java
            else -> PetOverlayService::class.java
        }

        fun intent(ctx: Context, instanceId: Int = 0): Intent =
            Intent(ctx, serviceClassFor(instanceId)).putExtra(EXTRA_INSTANCE, instanceId)

        /** 当前活跃实例号（含 0） */
        fun activeInstanceIds(): List<Int> = synchronized(activeInstances) { activeInstances.toList() }

        fun ensureRunning(ctx: Context, instanceId: Int = 0, persist: Boolean = true) {
            try {
                if (persist) ctx.startForegroundService(intent(ctx, instanceId))
                else ctx.startService(intent(ctx, instanceId))
            } catch (e: Exception) {
                // 8.0+ 后台启动前台服务限制：忽略（用户需从设置页手动启动）
                AppLog.log("SVC", "ensureRunning 失败(${if (persist) "前台" else "后台"}): ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stopAll(ctx: Context) {
            synchronized(activeInstances) {
                val ids = activeInstances.toList()
                ids.forEach { ctx.stopService(intent(ctx, it)) }
            }
            // 停掉所有服务类（含未在 activeInstances 中的）
            for (id in 0 until SLOT_COUNT) {
                runCatching { ctx.stopService(intent(ctx, id)) }
            }
        }

        private val activeInstances = mutableSetOf<Int>()
        /** 本进程内某实例初始化失败：避免 START_STICKY 无限重启循环 */
        private val initFailed = mutableSetOf<Int>()
    }

    internal val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                    // 协程内异常不得让应用崩溃，写入内置日志
                    AppLog.log("SCOPE", android.util.Log.getStackTraceString(e))
                }
    )
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var config: PetConfig
    private var instanceId = 0
    private var stateStore: PetState? = null

    private lateinit var wm: WindowManager
    private var container: View? = null
    private lateinit var videoView: PetVideoView
    private var params: WindowManager.LayoutParams? = null
    private var bubble: SpeechBubble? = null
    private var menuWindow: PetMenu? = null

    private lateinit var engine: PetEngine
    private lateinit var catalog: PetCatalog
    private var soundPool: SoundPool? = null
    private var clickSoundId = 0
    private var clickSoundId2 = 0

    // ---- 运行时设置快照（协程监听 DataStore 更新）----
    private var density = 1f
    private var curScale = 0.72
    private var curOpacity = 100
    internal var curSpeed = PetConfig.DEFAULT_PLAYBACK_SPEED
    private var curNoMove = false
    internal var curLock = false
    internal var curMouseThrough = false
    private var curShiftDrag = false
    internal var curPhysics = false
    internal var curCollision = true
    private var curGap = 0.0
    private var curSelfTalk = false
    private var curSelfTalkTexts: List<String> = emptyList()
    private var curSelfTalkDurationMs = 3200L
    private var curSelfTalkMinSec = 20
    private var curSelfTalkMaxSec = 60
    private var selfTalkRunnable: Runnable? = null
    private var curClickSound = true
    internal var curClickSoundChoice = "default"
    internal var curEdgePeek = false
    private var curClickBalance = false
    private var curClickSelfTalk = false
    private var visible = true
    private var consecutiveErrors = 0
    private var settingsJobs = mutableListOf<Job>()

    

    // ---- 手势状态 ----
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var grabOffsetX = 0f  // 按下时固定：手指 - 窗口左上角（px）
    private var grabOffsetY = 0f
    private var dragging = false
    private var longPressFired = false
    private var justDragged = false
    private var pressActive = false
    private val longPressRunnable = Runnable { onLongPress() }
    private var trail = mutableListOf<Triple<Long, Float, Float>>() // (timeMs, xDp, yDp)
    private var physPos = doubleArrayOf(0.0, 0.0)
    private var physVel = doubleArrayOf(0.0, 0.0)
    private var physMode: String? = null // null / drag / throw
    private var dragTargetX = 0
    private var dragTargetY = 0
    private var lastMoveMs = 0L

    // ---- 定时器 ----
    // 永续调度（空闲时只做空检查，开销可忽略）。原先条件不满足即停止且
    // 永不重启，导致物理拖动/后续自动走动的 tick 从未被调用。
    private val moveTicker = object : Runnable {
        override fun run() {
            // 动画间隔到点必须解除 gap 状态：否则一次动作/移动后
            // 引擎会永远停在待机/转向链（tickGap 原先无人调用）。
            engine.tickGap()
            if (engine.movePlanActive() || physMode != null) {
                engine.tickMove()
                tickPhysics()
            }
            uiHandler.postDelayed(this, 33)
        }
    }

    // ================================================================ 生命周期
    override fun onCreate() {
        super.onCreate()
        config = PetConfig.get(this)
        density = resources.displayMetrics.density
        AppLog.log("SVC", "onCreate instance=$instanceId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extraId = intent?.getIntExtra(EXTRA_INSTANCE, -1) ?: -1
        instanceId = if (extraId >= 0) extraId else defaultInstanceId
        val t0 = android.os.SystemClock.elapsedRealtime()
        AppLog.log(
            "SVC",
            "onStartCommand instance=$instanceId action=${intent?.action} startId=$startId " +
                    "重启=${intent == null}(null intent=系统重建) container=${container != null}"
        )
        synchronized(activeInstances) { activeInstances.add(instanceId) }
        // 先转前台再处理任何分支：startForegroundService 启动后若未在 5s 内
        // 调用 startForeground（例如权限被撤销直接 stopSelf），系统会抛
        // RemoteServiceException 让应用崩溃。
        startForeground(1000 + instanceId, buildNotification())
        when (intent?.action) {
            "quit" -> { quit(); return START_NOT_STICKY }
            "hide" -> { setHidden(true); return START_NOT_STICKY }
            "show" -> { setHidden(false); return START_NOT_STICKY }
            "toggle_island" -> { toggleIsland(); return START_NOT_STICKY }
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch { config.setPetRunning(true) }
        if (synchronized(initFailed) { initFailed.contains(instanceId) }) {
            // 上次初始化失败：不再重试，避免重启循环
            stopSelf()
            return START_NOT_STICKY
        }
        if (container == null) {
            scope.launch {
                try {
                    initPet()
                } catch (e: Throwable) {
                    AppLog.log(
                        "INIT",
                        "初始化失败: ${e.javaClass.simpleName}: ${e.message}\n${android.util.Log.getStackTraceString(e)}"
                    )
                    val rt = Runtime.getRuntime()
                    AppLog.log(
                        "INIT",
                        "失败时内存: 堆已用${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB/上限${rt.maxMemory() / 1048576}MB"
                    )
                    synchronized(initFailed) { initFailed.add(instanceId) }
                    showBubble("桌宠初始化失败：${e.message}")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.log(
            "SVC",
            "onDestroy instance=$instanceId（用户退出/服务被杀/初始化失败均会到这）"
        )
        synchronized(activeInstances) { activeInstances.remove(instanceId) }
        // 先停掉一切定时器/协程：否则 ticker 会在窗口已移除后继续 updateViewLayout
        uiHandler.removeCallbacksAndMessages(null)
        // 必须在 scope.cancel() 之前：savePosition 内部走 scope.launch
        // （engine 未初始化时不能碰，否则会记一条无意义异常）
        if (this::engine.isInitialized) savePosition()
        scope.cancel()
        settingsJobs.forEach { it.cancel() }
        // 先关掉所有子窗口（菜单/气泡/灵动岛/聊天/快捷对话）
        removeMenu()
        bubble?.dismiss()
        stopIsland()
        stopQuickChat()
        dismissChat()
        // 释放顺序：先停解码器并断开视频输出，再移除窗口（触发 GL 线程退出）。
        // 反过来（removeView → onDetachedFromWindow 里 release 纹理）会与
        // 仍在出帧的解码器/GL 线程竞态，可能打挂 SurfaceFlinger 导致整机假死。
        if (this::videoView.isInitialized) runCatching { videoView.release() }
        container?.let { runCatching { wm.removeView(it) } }
        collisionMember?.let { CollisionHub.unregister(it.id) }
        collisionMember = null
        CollisionHub.setOnMoved(instanceId, null)
        if (instanceId == 0) CollisionHub.onImpact = null
        stopAgentBus()
        // 音乐：桌宠全部退出后暂停（曲目/音量已持久化，下次打开继续沿用）
        music?.let { p ->
            runCatching { p.removeListener(musicListener) }
            if (activeInstanceIds().isEmpty()) runCatching { p.pause() }
        }
        music = null
        runCatching { soundPool?.release() }
        CoroutineScope(Dispatchers.Main).launch {
            if (activeInstanceIds().isEmpty()) config.setPetRunning(false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================================================================ 初始化
    private suspend fun initPet() {
        val t0 = android.os.SystemClock.elapsedRealtime()
        fun mem(): String {
            val rt = Runtime.getRuntime()
            return "堆${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB"
        }
        AppLog.log("SVC", "initPet start instance=$instanceId ${mem()}")
        stateStore = PetState(this, instanceId)
        catalog = PetCatalog.load(this)
        AppLog.log("SVC", "catalog 加载 ${catalog.names.size} 段动画 ${android.os.SystemClock.elapsedRealtime() - t0}ms ${mem()}")
        engine = PetEngine(
            catalog = catalog,
            play = { name -> playAnimation(name) },
            moveTo = { x, y -> moveWindow(x, y) },
            onFacingChanged = { f -> videoView.setMirror(engine.shouldMirror(engine.anim ?: "")); persistFacing(f) },
            onMoveFinished = { savePosition() },
        ).apply {
            noMove = curNoMove
            animationGapSeconds = curGap
            currentPositionSecProvider = { videoView.currentPositionMs() / 1000.0 }
        }

        // 窗口尺寸（物理像素，带上限保护：异常大 density 设备不溢出屏幕）
        curScale = config.scale()
        curMouseThrough = config.getBool("mouse_through", false)
        val (ww, wh) = windowSizePx()
        engine.setWindowSize(ww, wh)

        buildWindow()

        // 手势
        installGestures()
        // 无法选中（触摸穿透）初始应用
        applyTouchThrough()

        // 音效
        setupSound()

        // 恢复位置与朝向
        val st = stateStore!!.load()
        val (sx, sy) = screenPx()
        val savedX = st.x; val savedY = st.y
        // 默认右下角：以鱼身（内容区）为参照贴边（右 70%、底 92% 处）
        val defaultX = (sx - engine.winW * 0.70 - 24).toInt()
        val defaultY = (sy - engine.winH * 0.92 - 12).toInt()
        engine.setFacing(st.facing.ifEmpty { "left" })
        videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
        // 多开错位：新实例向右上偏移，避免与母体重叠
        val offsetX = if (instanceId > 0) (instanceId * 48).coerceAtMost(engine.winW / 2) else 0
        val offsetY = if (instanceId > 0) (instanceId * 32).coerceAtMost(engine.winH / 2) else 0
        val xr = windowXRange(); val yr = windowYRange()
        engine.setPosition(
            if (savedX >= 0) savedX.coerceIn(xr.first, xr.last)
            else (defaultX - offsetX).coerceIn(xr.first, xr.last),
            if (savedY >= 0) savedY.coerceIn(yr.first, yr.last)
            else (defaultY - offsetY).coerceIn(yr.first, yr.last),
        )
        syncEngineScreenBounds()
        AppLog.log("SVC", "窗口与手势就绪 ${android.os.SystemClock.elapsedRealtime() - t0}ms")

        // 气泡
        bubble = SpeechBubble(this, engine, config)
        bubble?.bubbleStyle = config.selfTalkBubbleStyle()
        bubble?.blurOn = config.blurEnabled() && Build.VERSION.SDK_INT >= 31

        // 播放速度（默认 1.5x）
        curSpeed = config.playbackSpeed()

        // 碰撞物理（默认开）
        curCollision = config.collisionEnabled()
        // 音效音量（上游 v4.0.5：0-100%）
        curSoundVolume = config.soundVolume() / 100f
        // 点击音效自选（default=内置 Q 弹 / duck=鸭子音效）
        curClickSound = config.clickSound()
        curClickSoundChoice = config.clickSoundChoice()
        // 功能开关：边缘探头
        curEdgePeek = config.edgePeekEnabled()
        // 点击台词绑定（上游 v4.1.0）
        curClickTalk = config.clickTalk()
        curThrowStrength = config.throwStrength()

        // 行为参数：移动概率/散步距离（设置可调）
        val moveProb = config.moveProbability()
        engine.pActs = (1.0 - moveProb).coerceIn(0.1, 0.95)
        engine.moveMinPx = config.moveMinPx()
        engine.moveMaxPx = config.moveMaxPx()

        // 自言自语素材缓存（供非协程回调使用）
        curSelfTalkTexts = config.selfTalkTexts().toList()
        curSelfTalkDurationMs = (config.selfTalkDuration() * 1000).toLong()
        curSelfTalk = config.selfTalkEnabled()
        curSelfTalkMinSec = config.selfTalkMin().coerceAtLeast(5)
        curSelfTalkMaxSec = config.selfTalkMax().coerceAtLeast(curSelfTalkMinSec)

        // 设置监听（改动即时生效）
        observeSettings()

        // 启动动画链
        engine.start()
        uiHandler.postDelayed(moveTicker, 33)

        // Agent 联动插件总线（上游统一事件协议；默认关，设置开启）
        if (config.agentLinkEnabled()) startAgentBus()

        // 灵动岛（设置开启则随主实例启动）
        if (instanceId == 0 && config.islandEnabled()) {
            island = DynamicIsland(this).also { it.show() }
        }

        // 多开碰撞物理（上游"鱼塘碰碰车"；默认开）
        collisionMember = CollisionHub.Member(
            id = instanceId,
            x = engine.winX.toDouble(), y = engine.winY.toDouble(),
            w = engine.winW, h = engine.winH,
        ).also { m ->
            CollisionHub.setEnabled(curCollision)
            CollisionHub.register(m)
            // 碰撞结算写回：本实例被撞 → 更新窗口/被撞飞进入抛掷
            CollisionHub.setOnMoved(instanceId) { member ->
                uiHandler.post {
                    if (dragging) return@post
                    val speed = kotlin.math.hypot(member.vx, member.vy)
                    if (physMode == null) {
                        if (speed > 60) {
                            // 静止的鱼被撞飞
                            physPos = doubleArrayOf(member.x, member.y)
                            physVel = doubleArrayOf(member.vx, member.vy)
                            physMode = "throw"
                        } else {
                            moveWindow(member.x.roundToInt(), member.y.roundToInt())
                            savePosition()
                        }
                    } else {
                        // 抛掷中的鱼：采纳碰撞后的速度
                        physVel = doubleArrayOf(member.vx, member.vy)
                    }
                }
            }
        }
        // 碰撞音效（全局单槽：只由主实例注册，避免多开时重复播放）
        if (instanceId == 0) {
            CollisionHub.onImpact = { playCollisionSound() }
        }
        scheduleSelfTalk()
        // 音乐播放（进程级单例，多开共用；播放时桌宠一直播「悠闲哼歌」）
        setupMusic()
        applyOpacity()
        AppLog.log(
            "SVC",
            "initPet 完成 ${android.os.SystemClock.elapsedRealtime() - t0}ms，窗口 ${engine.winX},${engine.winY} ${engine.winW}x${engine.winH} ${mem()}"
        )
    }

    private var collisionMember: CollisionHub.Member? = null
    private var island: DynamicIsland? = null
    private var quickChat: QuickChat? = null

    /** 双击间隔（毫秒）内二连击 → 快速对话 */
    private var lastTapMs = 0L

    // ---- 弹弓弹射（上游 PR#33）----
    private var slingshotAiming = false
    private var slingshotAnchorX = 0f
    private var slingshotAnchorY = 0f
    private var curThrowStrength = "standard"
    internal var curClickTalk = ""

    /** 桌宠显示/隐藏（灵动岛单击；仅主实例处理） */
    private fun setHidden(hidden: Boolean) {
        if (instanceId != 0) return
        petHidden = hidden
        visible = !hidden
        val c = container
        if (c != null) {
            uiHandler.post { c.visibility = if (hidden) View.GONE else View.VISIBLE }
            if (hidden) videoView.pausePlay() else videoView.resumePlay()
            AppLog.log("SVC", "桌宠 ${if (hidden) "隐藏" else "显示"}")
        }
    }

    /** 灵动岛单击：桌宠显示/隐藏切换（同进程直调，见 DynamicIsland.togglePet） */
    fun togglePetHidden() {
        if (instanceId != 0) return
        setHidden(!petHidden)
    }

    fun toggleIsland() {
        if (instanceId != 0) return
        if (island == null) {
            island = DynamicIsland(this).also { it.show() }
        } else {
            island?.dismiss()
            island = null
        }
    }

    private fun stopIsland() {
        island?.dismiss()
        island = null
    }

    private fun toggleQuickChat() {
        if (quickChat?.isShowing() == true) {
            quickChat?.dismiss()
        } else {
            if (quickChat == null) quickChat = QuickChat(this)
            quickChat?.show(engine.winX + engine.winW / 2, engine.winY)
        }
    }

    private fun stopQuickChat() {
        quickChat?.dismiss()
        quickChat = null
    }

    // ================================================================ Agent 联动（插件总线）
    private var agentBus: com.dshpet.android.plugin.AgentEventBus? = null

    private fun playCollisionSound() {
        if (curClickSound) playClickSound()
    }

    private fun startAgentBus() {
        if (agentBus != null) return
        agentBus = com.dshpet.android.plugin.AgentEventBus(this) { agent, state ->
            onAgentState(agent, state)
        }.also { it.start() }
    }

    private fun stopAgentBus() {
        agentBus?.stop()
        agentBus = null
    }

    /** 六态 → 动画/气泡（上游 agent_link_presentation 同款映射） */
    private fun onAgentState(agent: String, state: String) {
        AppLog.log("PLUGIN", "Agent[$agent] 状态: $state")
        when (state) {
            "thinking", "working" -> {
                // 联动动作池轮换（写代码/敲击为主）
                val pool = listOf("写代码", "原地敲击桌面互动", "吃Token", "深度思考碎碎念")
                    .filter { engine.hasAnim(it) }
                if (pool.isNotEmpty()) {
                    engine.switch(pool[kotlin.random.Random.nextInt(pool.size)])
                }
            }
            "attention" -> showBubble("主人，Agent（$agent）这边需要你看一眼～", 6000)
            "error" -> showBubble("Agent（$agent）执行好像遇到报错了…", 6000)
            "idle", "sleeping" -> engine.switchToIdle()
        }
    }

    // ================================================================ 自言自语
    private fun scheduleSelfTalk() {
        selfTalkRunnable?.let { uiHandler.removeCallbacks(it) }
        if (!curSelfTalk) return
        val min = curSelfTalkMinSec.coerceAtLeast(5)
        val max = curSelfTalkMaxSec.coerceAtLeast(min)
        val delay = (min + kotlin.random.Random.nextDouble() * (max - min)) * 1000
        val r = Runnable {
            showRandomSelfTalk()
            scheduleSelfTalk()
        }
        selfTalkRunnable = r
        uiHandler.postDelayed(r, delay.toLong())
    }

    private fun showRandomSelfTalk() {
        if (curSelfTalkTexts.isEmpty()) return
        val text = curSelfTalkTexts[kotlin.random.Random.nextInt(curSelfTalkTexts.size)]
        bubble?.showText(text, durationMs = curSelfTalkDurationMs.coerceIn(1000, 30000))
    }

    private fun buildWindow() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val w = engine.winW
        val h = engine.winH
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "dsh-pet-$instanceId"
        }
        params = lp

        // 纯代码创建视图（避免 XML inflate 自定义 View 的兼容性问题）
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        videoView = PetVideoView(this)
        // MATCH_PARENT：子视图始终跟随窗口尺寸（缩放改尺寸时不会与窗口脱节）
        root.addView(videoView, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.clipChildren = true
        AppLog.log("SVC", "buildWindow ${w}x$h 纯代码创建视图")
        videoView.setListener(object : PetVideoView.Listener {
            override fun onVideoEnded(name: String) {
                consecutiveErrors = 0
                engine.onAnimEnded(name)
                videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
            }

            override fun onVideoError(name: String, msg: String) {
                // 出错时推进动画链，避免停摆；连续出错则停止并提示（防死循环）
                AppLog.log("VIDEO", "播放失败 [$name]: $msg")
                consecutiveErrors++
                if (consecutiveErrors > 8) {
                    videoView.pausePlay()
                    showBubble("动画加载失败：$msg")
                } else {
                    engine.onAnimEnded(name)
                }
            }
        })
        container = root
        wm.addView(root, lp)
    }

    // ================================================================ 动画播放
    private fun playAnimation(name: String) {
        try {
            val path = catalog.files[name] ?: return
            videoView.play(name, path, curSpeed.toFloat())
            videoView.setMirror(engine.shouldMirror(name))
            // 隐藏时不播（省电）：显示时恢复
            if (!visible) videoView.pausePlay()
        } catch (e: Throwable) {
            AppLog.log("PLAY", "playAnimation($name) 异常: ${e.message}")
        }
    }

    // ================================================================ 手势
    private fun installGestures() {
        val v = container ?: return
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressActive = true
                    dragging = false
                    longPressFired = false
                    justDragged = false
                    downX = ev.x; downY = ev.y
                    downRawX = ev.rawX; downRawY = ev.rawY
                    grabOffsetX = downRawX - engine.winX
                    grabOffsetY = downRawY - engine.winY
                    lastMoveMs = System.currentTimeMillis()
                    trail.clear()
                    trail.add(Triple(lastMoveMs, ev.rawX, ev.rawY))
                    physPos = doubleArrayOf(engine.winX.toDouble(), engine.winY.toDouble())
                    physVel = doubleArrayOf(0.0, 0.0)
                    physMode = null
                    engine.cancelMove()
                    // 长按菜单（默认 500ms；"仅长按可拖动"时同样先长按）。
                    // 锁定位置时也必须保留长按：否则菜单打不开，用户无法再解锁。
                    uiHandler.removeCallbacks(longPressRunnable)
                    uiHandler.postDelayed(longPressRunnable, if (curShiftDrag) 300 else 500)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 拖拽中第二指按下：进入弹弓蓄力模式（锚点=当前指尖）
                    if (dragging && ev.pointerCount >= 2) {
                        slingshotAiming = true
                        slingshotAnchorX = ev.rawX
                        slingshotAnchorY = ev.rawY
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pressActive) return@setOnTouchListener true
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (slingshotAiming) {
                        // 蓄力中：桌宠被"拉"向锚点反方向（视觉跟随）
                        val pullX = slingshotAnchorX - ev.rawX
                        val pullY = slingshotAnchorY - ev.rawY
                        val pull = hypot(pullX.toDouble(), pullY.toDouble())
                        if (pull in 24.0..160.0) {
                            // 拉伸距离内：桌宠朝拉的反方向偏移（弹弓形变感）
                            moveWindow(
                                (downRawX - grabOffsetX - pullX * 0.3f).toInt(),
                                (downRawY - grabOffsetY - pullY * 0.3f).toInt(),
                            )
                        }
                        return@setOnTouchListener true
                    }
                    val threshold = (PetEngine.DRAG_THRESHOLD * curScale * density).coerceAtLeast(12.0)
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > threshold) {
                        if (curLock && !peeking) {
                            // 锁定位置：忽略拖动（保留长按菜单，不设无限质量）
                            return@setOnTouchListener true
                        }
                        collisionMember?.infiniteMass = true
                        if (curShiftDrag && !longPressFired) {
                            // 仅长按可拖动：未长按的拖动被忽略，且取消按压状态
                            pressActive = false
                            uiHandler.removeCallbacks(longPressRunnable)
                            return@setOnTouchListener true
                        }
                        // 边缘探头状态下把桌宠抓起来：立即脱离贴边（保持当前位置），跟手拖动
                        if (peeking) exitPeek(restorePosition = false)
                        uiHandler.removeCallbacks(longPressRunnable)
                        dragging = true
                        engine.onDragStart()
                    }
                    if (dragging) {
                        if (curPhysics) {
                            val now = System.currentTimeMillis()
                            trail.add(Triple(now, ev.rawX, ev.rawY))
                            val cutoff = now - (PetEngine.TRAIL_KEEP_SEC * 1000).toLong()
                            trail = trail.filter { it.first >= cutoff }.toMutableList()
                            dragTargetX = (ev.rawX - grabOffsetX).toInt()
                            dragTargetY = (ev.rawY - grabOffsetY).toInt()
                            physMode = "drag"
                        } else {
                            moveWindow(
                                (ev.rawX - grabOffsetX).toInt(),
                                (ev.rawY - grabOffsetY).toInt(),
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    uiHandler.removeCallbacks(longPressRunnable)
                    // 拖拽结束必须无条件复位（锁定/取消路径也会置位）
                    collisionMember?.infiniteMass = false
                    if (dragging) {
                        justDragged = true
                        uiHandler.postDelayed({ justDragged = false }, 150)
                        engine.onDragEnd()
                        // 弹弓发射：按蓄力拉伸发射
                        if (slingshotAiming) {
                            slingshotAiming = false
                            val pullX = (slingshotAnchorX - ev.rawX).toDouble()
                            val pullY = (slingshotAnchorY - ev.rawY).toDouble()
                            val dist = hypot(pullX, pullY).coerceIn(24.0, 160.0)
                            val cap = PetEngine.throwSpeedCap(curThrowStrength)
                            val speed = PetEngine.softClampSpeed(
                                900.0 * (dist / 160.0) * (cap / 4800.0), cap
                            )
                            if (dist > 24 && speed > 1) {
                                val len = max(hypot(pullX, pullY), 1e-6)
                                physVel = doubleArrayOf(
                                    pullX / len * speed,
                                    pullY / len * speed,
                                )
                                physPos = doubleArrayOf(engine.winX.toDouble(), engine.winY.toDouble())
                                physMode = "throw"
                            }
                            dragging = false
                            pressActive = false
                            return@setOnTouchListener true
                        }
                        if (curPhysics) {
                            val now = System.currentTimeMillis()
                            val (vx, vy) = PetEngine.estimateReleaseVelocity(trail, now)
                            if (hypot(vx, vy) < PetEngine.DEAD_ZONE_SPEED) {
                                physMode = null
                                savePosition()
                            } else {
                                physVel = doubleArrayOf(vx, vy)
                                physMode = "throw"
                            }
                        } else {
                            savePosition()
                        }
                        // 边缘探头：松手时若已贴到屏幕左/右缘 → 吸附探头
                        if (curEdgePeek) maybeEnterPeek()
                    } else if (!longPressFired && !justDragged) {
                        onTap()
                    } else if (longPressFired && curShiftDrag && !justDragged) {
                        // 仅长按可拖动：长按后未拖动直接松手 → 弹出菜单（否则无入口）
                        openMenu()
                    }
                    dragging = false
                    pressActive = false
                    true
                }
                else -> true
            }
        }
    }

    private fun onLongPress() {
        if (!pressActive) return
        longPressFired = true
        if (curShiftDrag) {
            // 仅长按可拖动：长按后进入"待拖动"状态，松手未移动则弹菜单
            return
        }
        openMenu()
    }

    private fun onTap() {
        // 双击 → 快速对话气泡（上游 PR#40）
        val now = System.currentTimeMillis()
        if (now - lastTapMs < 350) {
            lastTapMs = 0
            toggleQuickChat()
            return
        }
        lastTapMs = now
        engine.onTap()
        squash()
        // 点击台词绑定（上游 v4.1.0：自定义台词+动画）
        val talk = curClickTalk
        if (talk.isNotBlank()) {
            showBubble(talk, 4000)
        }
        if (curClickSound) playClickSound()
        if (curClickBalance) {
            showBalanceInBubble()
        } else if (curClickSelfTalk) {
            // 点击自言自语独立于周期性自言自语总开关
            showRandomSelfTalk()
        }
    }

    // ================================================================ Q 弹
    private var squashAnim: android.animation.ValueAnimator? = null

    private fun squash() {
        squashAnim?.cancel()
        videoView.pivotX = videoView.width / 2f
        videoView.pivotY = videoView.height.toFloat()
        val anim = android.animation.ValueAnimator.ofFloat(1f, 0.78f, 1.06f, 1f).apply {
            duration = 240
            addUpdateListener {
                videoView.scaleY = it.animatedValue as Float
            }
        }
        squashAnim = anim
        anim.start()
    }

    // ================================================================ 边缘探头
    /** 是否正贴在屏幕边缘探头（开启「边缘探头」后被拖到屏幕左/右缘时进入） */
    internal var peeking = false
    /** 退出探头时恢复的位置（进入探头瞬间记录的吸附前位置） */
    private var peekRestoreX = -1
    private var peekRestoreY = -1
    private var peekRestoreFacing = "left"

    /**
     * 边缘探头开关（用户设置）：开启后不再立即吸附，只有把桌宠**拖到屏幕左/右边缘**
     * 松手时才会贴边探头（[maybeEnterPeek]）。关闭时若正处于探头状态则退出并恢复原位。
     */
    private fun applyEdgePeek(on: Boolean) {
        curEdgePeek = on
        if (!on) {
            exitPeek(restorePosition = true)
        } else {
            showBubble("边缘探头已开启：把桌宠拖到屏幕左/右边缘试试～", 4000)
        }
    }

    /** 松手时判断是否贴在屏幕边缘；是则进入探头状态（贴左/贴右由位置决定） */
    private fun maybeEnterPeek() {
        if (!curEdgePeek || peeking) return
        val xr = windowXRange()
        val tol = (screenPx().first * 0.03).toInt().coerceIn(24, 96)
        val atLeft = engine.winX <= xr.first + tol
        val atRight = engine.winX >= xr.last - tol
        if (!atLeft && !atRight) return
        enterPeek(atLeft)
    }

    /**
     * 进入探头状态：宠物贴在屏幕左/右缘，只露出**左半 / 右半**身体，并呈 ±45° 斜角
     * （贴左缘头朝屏内右倾、贴右缘朝左倾），像从屏幕边斜着探头张望。
     *
     * 位置就是最初那版"窗口移出屏幕、露出 50% 宽度"的做法（纵向保持桌宠当前高度，
     * 不下坠）；旋转**由 GL 着色器完成**（[PetVideoView.setRotationDegrees]）——
     * 本视图是 zOrderOnTop 的独立 Surface 层，View.rotation 不生效还会造成拉伸，
     * 不能用。旋转绕窗口中心（≈角色身体中心），因此剪切线是斜的、头自然进屏内。
     * 期间引擎强制 idleOnly（只播待机动画）、禁止自动移动，并临时忽略碰撞推挤。
     */
    private fun enterPeek(toLeft: Boolean) {
        if (peeking) return
        val lp = params ?: return
        val c = container ?: return
        peeking = true
        peekRestoreX = engine.winX
        peekRestoreY = engine.winY
        peekRestoreFacing = engine.facing
        // 探头期间不接受抛掷物理 / 碰撞推挤（否则会把窗口挪出贴边位置）
        physMode = null
        collisionMember?.infiniteMass = true
        engine.cancelMove()
        engine.idleOnly = true
        engine.humActive = false
        val (sw, _) = screenPx()
        val ww = engine.winW
        val wh = engine.winH
        val visW = (ww * PEEK_VIS_W).toInt()   // 露出宽度 = 窗口一半
        val tilt = if (toLeft) PEEK_TILT else -PEEK_TILT
        // 画面旋转由着色器完成（独立 Surface 层不支持 View.rotation）。
        // 旋转绕窗口中心（= 角色身体中心），并补偿头部的纵向位移，避免高度跳变。
        videoView.setRotationDegrees(tilt)
        val rad = Math.toRadians(tilt.toDouble())
        val headDy = (PEEK_HEAD_CY - 0.5f) * wh      // 头部相对窗口中心的 y 偏移（负=在上方）
        val dyShift = (headDy * Math.cos(rad) - headDy).toFloat()
        val x = if (toLeft) -(ww - visW) else sw - visW
        val y = (engine.winY - dyShift.toInt()).coerceIn(windowYRange())
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(c, lp) }
        collisionMember?.let { it.x = x.toDouble(); it.y = y.toDouble(); it.w = ww; it.h = wh }
        engine.syncPosition(x, y)
        // 探头方向：贴左缘朝右看、贴右缘朝左看（面对屏内）
        engine.setFacing(if (toLeft) "right" else "left")
        videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
        persistFacing(engine.facing)
        engine.switchToIdle()
        savePosition()
        syncAnimLocks()
        showBubble("探头中～再拖动一下就能回来", 3000)
    }

    /** 退出探头状态。[restorePosition] = 恢复进入探头前的位置（被抓着拖走时传 false） */
    private fun exitPeek(restorePosition: Boolean) {
        if (!peeking) return
        peeking = false
        engine.idleOnly = false
        engine.noMove = curNoMove
        collisionMember?.infiniteMass = false
        // 复位探头期间的画面旋转（着色器 uniform，0 度 = 原样）
        videoView.setRotationDegrees(0f)
        val lp = params
        val c = container
        if (lp != null && c != null) {
            // 保险：把窗口尺寸恢复成完整尺寸（本版探头不改尺寸）
            lp.width = engine.winW
            lp.height = engine.winH
        }
        if (restorePosition) {
            val xr = windowXRange(); val yr = windowYRange()
            val rx = peekRestoreX.takeIf { it >= 0 }?.coerceIn(xr.first, xr.last)
            val ry = peekRestoreY.takeIf { it >= 0 }?.coerceIn(yr.first, yr.last)
            if (rx != null && ry != null && lp != null && c != null) {
                lp.x = rx
                lp.y = ry
                runCatching { wm.updateViewLayout(c, lp) }
                collisionMember?.let { it.x = rx.toDouble(); it.y = ry.toDouble() }
                engine.syncPosition(rx, ry)
            }
            engine.setFacing(peekRestoreFacing)
        } else {
            // 保持当前（贴边）位置，但收敛到正常可拖动范围，避免拖动第一帧跳变
            val nx = engine.winX.coerceIn(windowXRange())
            val ny = engine.winY.coerceIn(windowYRange())
            if (lp != null && c != null) {
                lp.x = nx
                lp.y = ny
                runCatching { wm.updateViewLayout(c, lp) }
            }
            engine.syncPosition(nx, ny)
        }
        videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
        engine.switchToIdle()
        savePosition()
        syncAnimLocks()
    }

    // ================================================================ 移动窗口
    private fun moveWindow(xPx: Int, yPx: Int) {
        val lp = params ?: return
        val c = container ?: return
        // 溢出 clamp：鱼身视觉贴边（见 overflowX 注释）
        val cx = xPx.coerceIn(windowXRange())
        val cy = yPx.coerceIn(windowYRange())
        lp.x = cx
        lp.y = cy
        runCatching { wm.updateViewLayout(c, lp) }
        collisionMember?.let {
            it.x = cx.toDouble(); it.y = cy.toDouble()
            it.w = engine.winW; it.h = engine.winH
        }
        engine.syncPosition(cx, cy)
        // 气泡跟随桌宠（自言自语气泡锚定在桌宠上/下方，必须一起移动）
        bubble?.follow()
    }

    private fun tickPhysics() {
        val mode = physMode ?: return
        val xr = windowXRange(); val yr = windowYRange()
        val left = xr.first
        val top = yr.first
        val right = xr.last
        val bottom = yr.last
        if (right <= left || bottom <= top) { physMode = null; return }
        if (mode == "drag") {
            val k = 200.0; val c = 30.0
            val dt = 0.016
            val tx = dragTargetX.toDouble()
            val ty = dragTargetY.toDouble()
            val vx = physVel[0] + ((tx - physPos[0]) * k - physVel[0] * c) * dt
            val vy = physVel[1] + ((ty - physPos[1]) * k - physVel[1] * c) * dt
            physVel[0] = vx; physVel[1] = vy
            physPos[0] += vx * dt; physPos[1] += vy * dt
            moveWindow(physPos[0].roundToInt(), physPos[1].roundToInt())
        } else {
            val dt = 0.016
            // 碰撞成员速度喂入（其它小肥鱼据此与我碰撞）
            val m = collisionMember
            if (m != null) {
                m.vx = physVel[0]; m.vy = physVel[1]
            }
            val r = PetEngine.throwStep(
                physPos[0], physPos[1], physVel[0], physVel[1], dt,
                left, top, right, bottom,
            )
            physPos = doubleArrayOf(r.px, r.py)
            physVel = doubleArrayOf(r.vx, r.vy)
            if (m != null) {
                m.vx = physVel[0]; m.vy = physVel[1]
            }
            moveWindow(physPos[0].roundToInt(), physPos[1].roundToInt())
            val speed = hypot(r.vx, r.vy)
            if (PetEngine.isAtRest(r.py, r.vx, r.vy, bottom, r.bounced, speed)) {
                physMode = null
                savePosition()
            }
        }
    }

    // ================================================================ 设置监听
    private fun observeSettings() {
        fun watch(
            flow: kotlinx.coroutines.flow.Flow<*>,
            apply: suspend (Any?) -> Unit,
        ) {
            settingsJobs.add(scope.launch {
                flow.collect { apply(it) }
            })
        }
        val c = config
        watch(c.flowDouble("scale", 0.72)) { v ->
            curScale = (v as Double)
            // 探头中窗口被缩成可见框：此时改尺寸会把探头框撑破，等退出后再套用
            if (peeking) return@watch
            // 缩放变化：重建窗口尺寸，保留脚底位置
            val oldW = engine.winW
            val (ww, wh) = windowSizePx()
            engine.setWindowSize(ww, wh)
            if (oldW != engine.winW) {
                val lp = params ?: return@watch
                val c = container ?: return@watch
                lp.width = ww
                lp.height = wh
                runCatching { wm.updateViewLayout(c, lp) }
                // 保持右下角锚点（以鱼身为参照）
                val (sw, sh) = screenPx()
                engine.setPosition(
                    (sw - engine.winW * 0.70 - 24).toInt().coerceAtLeast(0),
                    (sh - engine.winH * 0.92 - 12).toInt().coerceAtLeast(0),
                )
                syncEngineScreenBounds()
            }
        }
        watch(c.flowInt("pet_opacity", 100)) { v -> curOpacity = (v as Int).coerceIn(10, 100); applyOpacity() }
        watch(c.flowDouble("playback_speed", PetConfig.DEFAULT_PLAYBACK_SPEED)) { v -> curSpeed = v as Double; videoView.setPlaybackSpeed(curSpeed.toFloat()) }
        watch(c.flowDouble("move_probability", 0.20)) { v ->
            engine.pActs = (1.0 - (v as Double)).coerceIn(0.1, 0.95)
        }
        watch(c.flowInt("move_min_px", 60)) { v -> engine.moveMinPx = v as Int }
        watch(c.flowInt("move_max_px", 240)) { v ->
            engine.moveMaxPx = (v as Int).coerceAtLeast(engine.moveMinPx)
        }
        watch(c.flowBool("no_move", false)) { v -> curNoMove = v as Boolean; syncAnimLocks() }
        watch(c.flowBool("lock_position", false)) { v -> curLock = v as Boolean }
        watch(c.flowBool("mouse_through", false)) { v -> curMouseThrough = v as Boolean; applyTouchThrough() }
        watch(c.flowBool("shift_drag", false)) { v -> curShiftDrag = v as Boolean }
        watch(c.flowString("throw_strength", "standard")) { v -> curThrowStrength = v as String }
        watch(c.flowString("click_talk", "")) { v -> curClickTalk = v as String }
        watch(c.flowBool("drag_physics", false)) { v -> curPhysics = v as Boolean }
        watch(c.flowBool("pet_collision", true)) { v ->
            curCollision = v as Boolean
            CollisionHub.setEnabled(curCollision)
        }
        watch(c.flowBool("island_enabled", false)) { v ->
            if (instanceId == 0) {
                if (v as Boolean) toggleIsland() else stopIsland()
            }
        }
        watch(c.flowBool("agent_link_enabled", false)) { v ->
            if (v as Boolean) startAgentBus() else stopAgentBus()
        }
        watch(c.flowDouble("animation_gap_seconds", 0.0)) { v -> curGap = v as Double; engine.animationGapSeconds = curGap }
        watch(c.flowInt("sound_volume", 100)) { v -> curSoundVolume = (v as Int).coerceIn(0, 100) / 100f }
        watch(c.flowBool("click_sound_enabled", true)) { v -> curClickSound = v as Boolean }
        watch(c.flowString("click_sound_choice", "default")) { v ->
            curClickSoundChoice = if (v == "duck") "duck" else "default"
        }
        // 音乐音量（设置页滑块；与点击音效音量独立）
        watch(c.flowInt("music_volume", 80)) { v -> musicPlayer().setVolume((v as Int).coerceIn(0, 100) / 100f) }
        // 曲目下标（其它入口改过时同步，播放中不打断）
        watch(c.flowInt("music_index", 0)) { v ->
            val p = music
            val i = (v as Int).coerceAtLeast(0)
            if (p != null && !p.isPlaying() && i != p.index) p.index = i
        }
        watch(c.flowBool("edge_peek_enabled", false)) { v ->
            val on = v as Boolean
            if (on == curEdgePeek) return@watch
            applyEdgePeek(on)
        }
        
        watch(c.flowBool("click_show_balance", false)) { v -> curClickBalance = v as Boolean }
        watch(c.flowBool("click_show_self_talk", false)) { v -> curClickSelfTalk = v as Boolean }
        watch(c.flowBool("self_talk_enabled", false)) { v ->
            curSelfTalk = v as Boolean
            if (!curSelfTalk) bubble?.hide()
            else scheduleSelfTalk()
        }
        watch(c.flowString("facing", "left")) { v ->
            engine.setFacing(v as String)
            videoView.setMirror(engine.shouldMirror(engine.anim ?: ""))
        }
        watch(c.flowStringSet("self_talk_texts", emptySet())) { v ->
            curSelfTalkTexts = (v as Set<*>).filterIsInstance<String>().toList()
        }
        watch(c.flowString("self_talk_bubble_style", "classic_top")) { v ->
            bubble?.bubbleStyle = v as String
        }
        watch(c.flowBool("blur_enabled", false)) { v ->
            bubble?.blurOn = (v as Boolean) && Build.VERSION.SDK_INT >= 31
        }
        watch(c.flowDouble("self_talk_duration_seconds", 3.2)) { v ->
            curSelfTalkDurationMs = ((v as Double) * 1000).toLong()
        }
        watch(c.flowInt("self_talk_min_interval", 20)) { v ->
            curSelfTalkMinSec = (v as Int).coerceAtLeast(5)
            scheduleSelfTalk()
        }
        watch(c.flowInt("self_talk_max_interval", 60)) { v ->
            curSelfTalkMaxSec = (v as Int).coerceAtLeast(curSelfTalkMinSec)
            scheduleSelfTalk()
        }
    }

    private fun applyOpacity() {
        val lp = params ?: return
        val c = container ?: return
        lp.alpha = curOpacity / 100f
        runCatching { wm.updateViewLayout(c, lp) }
    }

    /** 无法选中：整窗 FLAG_NOT_TOUCHABLE，触摸事件直接穿透到下层（点击/拖动均不响应） */
    private fun applyTouchThrough() {
        val lp = params ?: return
        val c = container ?: return
        if (curMouseThrough) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(c, lp) }
    }



    fun showBubble(text: String, durationMs: Long = 6000) {
        uiHandler.post { bubble?.showText(text, durationMs) }
    }

    // ================================================================ 菜单
    private fun openMenu() {
        removeMenu()
        menuWindow = PetMenu(
            service = this,
            engine = engine,
            onDismiss = { removeMenu() },
        ).apply { show() }
    }

    fun removeMenu() {
        menuWindow?.dismiss()
        menuWindow = null
    }

    // ================================================================ 其他功能
    fun spawnPet() {
        scope.launch {
            val active = activeInstanceIds()
            val cap = maxInstances
            if (cap > 0 && active.size >= cap) {
                showBubble("最多同时 $cap 只小肥鱼～", 3000)
                return@launch
            }
            if (active.size >= SLOT_COUNT) {
                showBubble("已达槽位上限（$SLOT_COUNT 只）～", 3000)
                return@launch
            }
            // 复用最小空闲 id
            val id = (1 until SLOT_COUNT).firstOrNull { it !in active } ?: return@launch
            PetOverlayService.ensureRunning(this@PetOverlayService, id)
            showBubble("一只新的小肥鱼诞生啦！", 4000)
        }
    }

    fun openSettings() {
        MainActivity.start(this)
    }

    // 悬浮 AI 对话窗口（长按菜单入口）
    private var chatWindow: PetChatWindow? = null

    fun openChat() {
        if (chatWindow == null) chatWindow = PetChatWindow(this)
        chatWindow?.show()
    }

    private fun dismissChat() {
        chatWindow?.dismiss()
        chatWindow = null
    }

    fun showBalanceInBubble() {
        scope.launch {
            val apiKey = config.chatApiKey()
            if (apiKey.isBlank()) {
                showBubble("未配置 AI API Key，无法查询余额", 6000)
                return@launch
            }
            showBubble("让我看看余额…", 6000)
            val r = withContext(Dispatchers.IO) {
                Balance.fetch(config.chatBaseUrl(), apiKey, config.chatVerifySsl(), config.chatTimeout())
            }
            r.fold(
                onSuccess = { txt ->
                    // 余额峰谷提示（上游 v4.0.4）：北京时间峰谷档位 + 下一切换时间
                    val tier = Balance.pricingTierText()
                    showBubble("$txt\n$tier", 6500)
                    // 余额分档动画（上游 v4.0.4）：数值可得时按档位播动画
                    val num = Regex("¥([0-9.]+)").find(txt)?.groupValues?.get(1)?.toDoubleOrNull()
                    if (num != null) {
                        val tier = Balance.tierIndexFor(num)
                        val anim = Balance.TIER_ANIMS.getOrNull(tier)
                        if (anim != null && engine.hasAnim(anim)) {
                            engine.switch(anim)
                        }
                    }
                },
                onFailure = { showBubble("余额查询失败：${it.message}", 7000) },
            )
        }
    }

    fun checkUpdate() {
        scope.launch {
            showBubble("正在检查更新…", 6000)
            val r = withContext(Dispatchers.IO) { Updater.latestRelease() }
            r.fold(
                onSuccess = { rel ->
                    val current = com.dshpet.android.BuildConfig.VERSION_NAME
                    if (Updater.isNewer(rel.tag, current)) {
                        showBubble("发现新版本 v${rel.tag}（当前 $current）。可前往设置-关于下载更新。", 9000)
                    } else {
                        showBubble("已经是最新版本（$current）啦", 6000)
                    }
                },
                onFailure = { showBubble("检查更新失败：${it.message}", 7000) },
            )
        }
    }

    fun toggleVisible() {
        visible = !visible
        container?.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) videoView.resumePlay() else videoView.pausePlay()
        bubble?.hide()
    }

    /** 回到右下角（默认角落） */
    fun returnToCorner() {
        val (sw, sh) = screenPx()
        val cornerX = (sw - engine.winW - 24).coerceAtLeast(0)
        val cornerY = (sh - engine.winH - 24).coerceAtLeast(0)
        if (peeking) {
            // 先退出边缘探头：把"恢复位置"改成目标角落，退出时会落到这里
            peekRestoreX = cornerX
            peekRestoreY = cornerY
            exitPeek(restorePosition = true)
            return
        }
        engine.cancelMove()
        engine.setPosition(cornerX, cornerY)
        savePosition()
    }

    fun quit() {
        // 仅退出本实例（其它小肥鱼不受影响）。
        // 先同步关掉所有子窗口：即便 onDestroy 被延迟/异常，也不会留下
        // 悬浮窗残留（残留的可聚焦/触碰窗口会让整屏触摸失效）。
        runCatching {
            removeMenu()
            bubble?.dismiss()
            stopQuickChat()
            dismissChat()
            stopIsland()
        }
        stopSelf()
    }

    // ================================================================ 位置持久化
    private fun savePosition() {
        scope.launch {
            stateStore?.save(PetState.State(engine.winX, engine.winY, engine.facing))
        }
    }

    private fun persistFacing(f: String) {
        scope.launch { config.setFacing(f) }
    }

    // ================================================================ 音乐播放
    /** 进程级音乐播放器（多开实例共用同一播放器与播放列表） */
    private var music: PetMusicPlayer? = null
    /** 上次应用的哼歌锁状态（避免每帧重复切动画） */
    private var humApplied = false

    /** 播放状态变化 → 同步桌宠动画（哼歌） */
    private val musicListener: (PetMusicPlayer.State) -> Unit = { syncAnimLocks() }

    /** 取得播放器并接好状态回调（曲目下标写回 DataStore） */
    internal fun musicPlayer(): PetMusicPlayer = music ?: PetMusicPlayer.get(this).also {
        music = it
        it.addListener(musicListener)
        it.onIndexChanged = { idx -> scope.launch { config.setMusicIndex(idx) } }
    }

    private suspend fun setupMusic() {
        val p = musicPlayer()
        p.index = config.musicIndex()
        p.setVolume(config.musicVolume() / 100f)
        // 只扫描曲目列表；播放器等到用户点播放/切歌时才创建
        p.reload()
        engine.humTune = HUMMING_ANIM.takeIf { n -> engine.hasAnim(n) }
        syncAnimLocks()
    }

    /**
     * 同步动画锁，优先级：边缘探头（idleOnly）> 音乐哼歌 > 不移动。
     * 音乐播放中且未处于边缘探头状态 → 引擎始终播放「悠闲哼歌」。
     */
    internal fun syncAnimLocks() {
        if (!this::engine.isInitialized) return
        val p = music ?: return
        val hum = p.isPlaying() && !peeking && engine.hasAnim(HUMMING_ANIM)
        engine.humActive = hum
        if (hum != humApplied) {
            humApplied = hum
            if (hum) {
                engine.cancelMove()
                engine.switch(HUMMING_ANIM)
            } else if (engine.anim == HUMMING_ANIM) {
                engine.switchToIdle()
            }
        }
        engine.noMove = curNoMove || peeking || hum
    }

    // ---- 长按菜单音乐控制（左右键切歌 / 播放暂停 / 指定曲目）----
    // 全部包一层 runCatching：音乐是附加功能，任何异常都不得带崩桌宠进程
    fun musicToggle() {
        runCatching { musicPlayer().toggle() }.onFailure { AppLog.log("MUSIC", "播放/暂停失败: ${it.message}") }
        syncAnimLocks()
    }

    fun musicNext() {
        runCatching { musicPlayer().next() }.onFailure { AppLog.log("MUSIC", "下一首失败: ${it.message}") }
        syncAnimLocks()
    }

    fun musicPrev() {
        runCatching { musicPlayer().prev() }.onFailure { AppLog.log("MUSIC", "上一首失败: ${it.message}") }
        syncAnimLocks()
    }

    fun musicPlayAt(i: Int) {
        runCatching { musicPlayer().playAt(i) }.onFailure { AppLog.log("MUSIC", "选曲失败: ${it.message}") }
        syncAnimLocks()
    }

    // ================================================================ 音效
    private fun setupSound() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            soundPool = SoundPool.Builder().setMaxStreams(3).build()
        } else {
            @Suppress("DEPRECATION")
            soundPool = SoundPool(3, android.media.AudioManager.STREAM_MUSIC, 0)
        }
        clickSoundId = loadSound("pet/sounds/click.wav")
        clickSoundId2 = loadSound("pet/sounds/click2.wav")
    }

    /** 从 assets 加载音效；失败返回 0（不播放）。 */
    private fun loadSound(path: String): Int = try {
        val afd = assets.openFd(path)
        soundPool?.load(afd, 1) ?: 0
    } catch (e: Exception) { 0 }

    internal var curSoundVolume = 1f

    /**
     * 点击音效（v2.0.0 自选）：
     *  default → click.wav（内置 Q 弹）
     *  duck    → click2.wav（鸭子音效）
     * 不再交替播放。
     */
    private fun playClickSound(): Int {
        val id = when (curClickSoundChoice) {
            "duck" -> if (clickSoundId2 != 0) clickSoundId2 else clickSoundId
            else -> if (clickSoundId != 0) clickSoundId else clickSoundId2
        }
        if (id != 0) {
            val v = curSoundVolume
            soundPool?.play(id, v, v, 1, 0, 1f)
        }
        return id
    }

    // ================================================================ 通知
    private fun buildNotification(): Notification {
        val openSettings = PendingIntent.getActivity(
            this, 0, MainActivity.startIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openChat = PendingIntent.getActivity(
            this, 1, Intent(this, ChatActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .applyHideRecentsPolicy(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this, 2, intent(this, instanceId).setAction("quit"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pet)
            .setContentTitle(if (instanceId == 0) "dsh-pet 桌宠" else "dsh-pet 桌宠 #$instanceId")
            .setContentText("桌宠正在陪伴你 · 点击打开设置")
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "AI 对话", openChat)
            .addAction(0, "退出", quit)
            .build()
        return n
    }

    // ================================================================ 工具
    /** 屏幕物理像素 */
    private fun screenPx(): Pair<Int, Int> {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
        return bounds.width() to bounds.height()
    }

    /** 桌宠窗口物理像素尺寸：按屏幕宽度比例（scale=0.72 → 42% 屏宽），
     *  与设备 density 无关，异常大密度设备也不会溢出。 */
    private fun windowSizePx(): Pair<Int, Int> {
        val (sw, _) = screenPx()
        val frac = 0.42 * curScale / 0.72
        val w = (sw * frac).toInt().coerceIn(160, sw * 92 / 100)
        val h = w * 9 / 16
        return w to h
    }

    // ---- 溢出边界 ----
    // 素材画布（640x360）里常驻动画的内容只占约 x[30%..70%]、y[8%..92%]，
    // 窗口贴屏幕边时鱼身看起来悬空。允许窗口越出屏幕一段（≈透明边距），
    // 使鱼身而非不可见边框贴边。特效类动画（放烟花等）内容更宽，取保守值。
    private fun overflowX(): Int = (engine.winW * 0.26).toInt()
    private fun overflowY(): Int = (engine.winH * 0.10).toInt()

    /** 窗口水平可移动范围（含溢出） */
    private fun windowXRange(): IntRange {
        val (sw, _) = screenPx()
        val ov = overflowX()
        return -ov..maxOf(-ov, sw - engine.winW + ov)
    }

    /** 窗口垂直可移动范围（含溢出） */
    private fun windowYRange(): IntRange {
        val (_, sh) = screenPx()
        val ov = overflowY()
        return -ov..maxOf(-ov, sh - engine.winH + ov)
    }

    /** 同步引擎屏幕边界（自动散步范围；含溢出） */
    private fun syncEngineScreenBounds() {
        val xr = windowXRange()
        val yr = windowYRange()
        engine.screenLeft = xr.first
        engine.screenRight = xr.last + engine.winW
        engine.screenTop = yr.first
        engine.screenBottom = yr.last + engine.winH
    }

    fun requireOverlayPermission(): Boolean = Settings.canDrawOverlays(this)
}
