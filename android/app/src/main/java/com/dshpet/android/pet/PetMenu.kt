package com.dshpet.android.pet

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshpet.android.data.PetConfig
import com.dshpet.android.ui.theme.DshPetTheme
import com.dshpet.android.util.ProvideComposeHost
import com.dshpet.android.util.attachComposeHost
import com.dshpet.android.util.mdBlur
import kotlinx.coroutines.launch

/**
 * 长按桌宠弹出的 MD3 菜单（新版菜单布局，功能对齐桌面端 modern.json 分组）。
 */
class PetMenu(
    private val service: PetOverlayService,
    private val engine: PetEngine,
    private val onDismiss: () -> Unit,
) {
    private val ctx: Context = service
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null) return
        val composeView = ComposeView(ctx).apply {
            attachComposeHost()
            setContent { ProvideComposeHost { DshPetTheme { MenuRoot() } } }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        view = composeView
        params = lp
        composeView.isFocusableInTouchMode = true
        composeView.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismiss(); true
            } else false
        }
        composeView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss(); true
            } else false
        }
        runCatching { wm.addView(composeView, lp) }
        // 菜单定位：桌宠上方（放不下放下方）
        composeView.post { composeView.requestFocus(); locate() }
    }

    fun locate() {
        val v = view ?: return
        val p = params ?: return
        val bw = v.measuredWidth
        val bh = v.measuredHeight
        val (sw, sh) = screenPx()
        val petCx = engine.winX + engine.winW / 2
        var x = petCx - bw / 2
        x = x.coerceIn(8, maxOf(8, sw - bw - 8))
        var y = engine.winY - bh - 12
        if (y < 0) y = engine.winY + engine.winH + 12
        y = y.coerceIn(0, maxOf(0, sh - bh))
        p.x = x; p.y = y
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    /** 悬浮窗整体拖动（标题栏手势回调） */
    private fun onWindowDrag(dx: Float, dy: Float) {
        val p = params ?: return
        val v = view ?: return
        p.x += dx.toInt()
        p.y += dy.toInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun screenPx(): Pair<Int, Int> {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect(0, 0, ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
        }
        return bounds.width() to bounds.height()
    }

    // ================================================================ UI
    @Composable
    private fun MenuRoot() {
        val cfg = PetConfig.get(ctx)
        // 菜单缩放（设置-外观可调 0.7x..1.4x，紧凑布局默认即小菜单）
        val menuScale by cfg.flowDouble("menu_scale", 1.0).collectAsState(initial = 1.0)
        // 宠物大小（与设置页同一数据源；engine.winW 是物理像素，不能直接换算 dp 档位）
        val menuPetScale by cfg.flowDouble("scale", 0.72).collectAsState(initial = 0.72)
        val scaleF = menuScale.toFloat()
        var speedOpen by remember { mutableStateOf(false) }
        var sizeOpen by remember { mutableStateOf(false) }
        var animHubOpen by remember { mutableStateOf(false) }
        var quickOpen by remember { mutableStateOf(false) }
        var funcOpen by remember { mutableStateOf(false) }
        var musicOpen by remember { mutableStateOf(false) }
        val blurCfg by cfg.flowBool("blur_enabled", false).collectAsState(initial = false)
        val blurOn = blurCfg && Build.VERSION.SDK_INT >= 31
        // 写入必须挂在服务级作用域：菜单 onDismiss 会立刻销毁窗口组合，
        // rememberCoroutineScope 随之取消，DataStore 的 suspend 写入会被掐断
        // （开关"时灵时不灵"的根因）。服务作用域随服务销毁才取消，写入必完成。
        val run: (suspend () -> Unit) -> Unit = { action ->
            service.scope.launch { action() }
            onDismiss()
        }
        // 面板开关只写配置、不关菜单（方便连续切换）
        val runKeep: (suspend () -> Unit) -> Unit = { action ->
            service.scope.launch { action() }
        }

        // 功能面板开合后窗口尺寸变化 → 等下一帧布局完成后重新定位（紧贴桌宠、不超屏）
        androidx.compose.runtime.LaunchedEffect(funcOpen, musicOpen) {
            androidx.compose.runtime.withFrameNanos { }
            view?.post { locate() }
        }

        Surface(
            modifier = Modifier
                .width((236 * scaleF).dp)
                .graphicsLayer {
                    scaleX = scaleF; scaleY = scaleF
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
                .mdBlur(blurOn, radius = 22),
            shape = RoundedCornerShape(16.dp),
            color = if (blurOn) Color(0xE6FFFFFF) else MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                    // 标题栏：整条可拖动窗口，右侧直接关闭
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    onWindowDrag(dragAmount.x, dragAmount.y)
                                }
                            }
                            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Menu, contentDescription = "拖动",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(
                            "小肥鱼",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 6.dp),
                        )
                        IconButton(onClick = { dismiss() }, modifier = Modifier.height(28.dp)) {
                            Icon(
                                Icons.Filled.Close, contentDescription = "关闭",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(16.dp),
                            )
                        }
                    }
                    if (funcOpen) {
                        FuncPanel(cfg, blurOn, onBack = { funcOpen = false }, run, runKeep)
                    } else if (musicOpen) {
                        MusicPanel(blurOn, onBack = { musicOpen = false }, runKeep = runKeep)
                    } else if (animHubOpen) {
                        AnimHub(onBack = { animHubOpen = false }) { name -> run { engine.switch(name) } }
                    } else if (quickOpen) {
                        QuickLaunchList(cfg, onBack = { quickOpen = false }) { pkg ->
                            run {
                                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                                if (intent != null) runCatching { ctx.startActivity(intent) }
                            }
                        }
                    } else {
                        // ---- 互动 ----
                        MenuGroup("互动")
                        MenuItem(Icons.Filled.Send, "AI 对话") { run { service.openChat() } }
                        MenuItem(Icons.Filled.Star, "欧鲸鲸（彩蛋）") { run { service.spawnEasterEgg() } }
                        // ---- 播放 ----
                        MenuGroup("播放")
                        MenuItem(Icons.Filled.PlayArrow, "动画集") { animHubOpen = true }
                        MenuItem(Icons.Filled.Refresh, "播放速度", badge = "${service.curSpeed}×") { speedOpen = !speedOpen; sizeOpen = false }
                        SpeedSubmenu(speedOpen) { v -> run { cfg.setPlaybackSpeed(v) } }
                        MenuItem(Icons.Filled.Home, "大小", badge = if (service.curMouseThrough || service.curLock) "—" else sizeLabel(menuPetScale)) { sizeOpen = !sizeOpen; speedOpen = false }
                        SizeSubmenu(sizeOpen, menuPetScale) { v -> run { cfg.setScale(v) } }
                        // ---- 音乐（当前播放 + 左右键切歌）----
                        MenuGroup("音乐")
                        MusicBar(runKeep)
                        MenuItem(Icons.Filled.List, "音乐列表") { musicOpen = true }
                        // ---- 功能（展开功能页）----
                        MenuGroup("功能")
                        MenuItem(
                            Icons.Filled.Search,
                            "边缘探头",
                            badge = if (service.curEdgePeek) "开" else null,
                        ) { funcOpen = true }
                        MenuItem(
                            Icons.Filled.Star,
                            "更多功能",
                            badge = "▸",
                        ) { funcOpen = true }
                        // ---- 工具 ----
                        MenuGroup("工具")
                        MenuItem(Icons.Filled.Star, "DeepSeek 余额") { run { service.showBalanceInBubble() } }
                        MenuItem(Icons.Filled.Refresh, "检查更新") { run { service.checkUpdate() } }
                        MenuItem(Icons.Filled.PlayArrow, "快捷启动") { quickOpen = true }
                        // ---- 设置 ----
                        MenuGroup("设置")
                        MenuItem(Icons.Filled.Settings, "桌宠设置") { run { service.openSettings() } }
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                        // ---- 退出 ----
                        MenuItem(Icons.Filled.Close, "退出桌宠", danger = true) { run { service.quit() } }
                    }
            }
        }
    }

    /** 功能面板：边缘探头 / 点击音效自选 + 原功能分类项 */
    @Composable
    private fun FuncPanel(
        cfg: PetConfig,
        blurOn: Boolean,
        onBack: () -> Unit,
        run: (suspend () -> Unit) -> Unit,
        runKeep: (suspend () -> Unit) -> Unit,
    ) {
        var edgePeek by remember { mutableStateOf(service.curEdgePeek) }
        var noMove by remember { mutableStateOf(engine.noMove) }
        var lock by remember { mutableStateOf(service.curLock) }
        var mouseThrough by remember { mutableStateOf(service.curMouseThrough) }
        var physics by remember { mutableStateOf(service.curPhysics) }
        var soundChoice by remember { mutableStateOf(service.curClickSoundChoice) }
        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = if (blurOn) Color(0xE6FFFFFF) else MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                // 面板标题：返回主菜单（随主菜单整体拖动）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onWindowDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .clickable(onClick = onBack)
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("◀ 返回", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "功能",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                // ---- 新功能 ----
                MenuGroup("新功能")
                ToggleItem(Icons.Filled.Search, "边缘探头", edgePeek, sub = "拖到屏幕左右边缘自动贴边探头") {
                    edgePeek = !edgePeek
                    runKeep { cfg.setEdgePeek(edgePeek) }
                }
                // ---- 点击音效自选 ----
                MenuGroup("点击音效")
                ChoiceItem("默认音效", soundChoice == "default") {
                    soundChoice = "default"
                    runKeep { cfg.setClickSoundChoice("default") }
                }
                ChoiceItem("鸭子音效", soundChoice == "duck") {
                    soundChoice = "duck"
                    runKeep { cfg.setClickSoundChoice("duck") }
                }
                // ---- 原功能分类项 ----
                MenuGroup("功能")
                ToggleItem(Icons.Filled.Build, "拖动物理", physics) {
                    physics = !physics; run { cfg.setDragPhysics(physics) }
                }
                MenuItem(Icons.Filled.Home, "回到右下角") { run { service.returnToCorner() } }
                ToggleItem(Icons.Filled.Close, "不移动", noMove) {
                    noMove = !noMove; run { cfg.setNoMove(noMove) }
                }
                ToggleItem(Icons.Filled.Lock, "锁定位置", lock) {
                    lock = !lock; run { cfg.setLockPosition(lock) }
                }
                ToggleItem(Icons.Filled.Close, "无法选中", mouseThrough) {
                    mouseThrough = !mouseThrough
                    // 无法选中 + 锁定会令桌宠完全失联（菜单也点不开），开启时自动解除锁定
                    run {
                        if (mouseThrough) cfg.setLockPosition(false)
                        cfg.setMouseThrough(mouseThrough)
                    }
                }
                MenuItem(Icons.Filled.Add, "生小肥鱼（多开）") { run { service.spawnPet() } }
                MenuItem(Icons.Filled.Star, "灵动岛") { run { service.toggleIsland() } }
            }
        }
    }

    // ================================================================ 音乐（长按菜单）
    /** 订阅播放器状态：菜单打开期间实时刷新当前曲目 */
    @Composable
    private fun rememberMusicState(): PetMusicPlayer.State {
        val player = remember { service.musicPlayer() }
        var st by remember { mutableStateOf(player.state()) }
        DisposableEffect(player) {
            val l: (PetMusicPlayer.State) -> Unit = { st = it }
            player.addListener(l)
            onDispose { player.removeListener(l) }
        }
        return st
    }

    /** 音乐条：显示当前播放曲目，左右键切歌（点曲名 = 播放/暂停） */
    @Composable
    private fun MusicBar(runKeep: (suspend () -> Unit) -> Unit) {
        val st = rememberMusicState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { runKeep { service.musicPrev() } },
                enabled = st.hasTracks,
                modifier = Modifier.height(30.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft, contentDescription = "上一首",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { runKeep { service.musicToggle() } }
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(st.title, fontSize = 12.sp, maxLines = 1)
                Text(
                    if (!st.hasTracks) "未上传音乐（设置 → 桌宠 → 音乐）"
                    else "${st.position}/${st.total} · ${if (st.playing) "播放中" else "已暂停"}（点击播放/暂停）",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = { runKeep { service.musicNext() } },
                enabled = st.hasTracks,
                modifier = Modifier.height(30.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowRight, contentDescription = "下一首",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(22.dp),
                )
            }
        }
    }

    /** 音乐列表面板：当前曲目 + 全部曲目（点击播放）+ 上传提示（无歌词功能） */
    @Composable
    private fun MusicPanel(
        blurOn: Boolean,
        onBack: () -> Unit,
        runKeep: (suspend () -> Unit) -> Unit,
    ) {
        val player = remember { service.musicPlayer() }
        val st = rememberMusicState()
        var names by remember { mutableStateOf(player.trackNames()) }
        // 上传/删除音乐后（total 变化）重新读取列表
        androidx.compose.runtime.LaunchedEffect(st.total) { names = player.trackNames() }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = if (blurOn) Color(0xE6FFFFFF) else MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onWindowDrag(dragAmount.x, dragAmount.y)
                            }
                        }
                        .clickable(onClick = onBack)
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("◀ 返回", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "音乐",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                MusicBar(runKeep)
                HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                if (names.isEmpty()) {
                    Text(
                        "还没有音乐文件。前往 设置 → 桌宠 → 音乐 上传（支持 mp3 / m4a / flac / ogg / wav 等）。",
                        fontSize = 11.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                } else {
                    MenuGroup("播放列表（点击播放）")
                    names.forEachIndexed { i, name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { runKeep { service.musicPlayAt(i) } }
                                .padding(start = 20.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${i + 1}. $name",
                                fontSize = 12.sp,
                                maxLines = 1,
                                color = if (i == st.position - 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (i == st.position - 1) {
                                Icon(
                                    Icons.Filled.Check, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(14.dp),
                                )
                            }
                        }
                    }
                }
                Text(
                    "播放音乐时桌宠会一直播放「悠闲哼歌」（边缘探头时不播放）",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    @Composable
    private fun MenuGroup(title: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 1.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(11.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }

    @Composable
    private fun MenuItem(
        icon: ImageVector,
        title: String,
        badge: String? = null,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp),
            )
            Text(
                text = title,
                fontSize = 13.sp,
                maxLines = 1,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            )
            badge?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ToggleItem(
        icon: ImageVector,
        title: String,
        checked: Boolean,
        sub: String? = null,
        onToggle: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(18.dp))
            if (sub == null) {
                Text(title, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp).weight(1f))
            } else {
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(title, fontSize = 13.sp, maxLines = 1)
                    Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Switch(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.height(24.dp))
        }
    }

    /** 单选行（音效选择等）：选中项带对勾 */
    @Composable
    private fun ChoiceItem(title: String, selected: Boolean, onPick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPick)
                .padding(start = 26.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title, fontSize = 13.sp, maxLines = 1,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
            }
        }
    }

    @Composable
    private fun SpeedSubmenu(open: Boolean, onPick: (Double) -> Unit) {
        if (!open) return
        listOf(1.0, 1.25, 1.5, 1.75, 2.0).forEach { v ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(v) }
                    .padding(start = 36.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Text("${v}×", fontSize = 12.sp)
                if (service.curSpeed == v) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    @Composable
    private fun SizeSubmenu(open: Boolean, currentScale: Double, onPick: (Double) -> Unit) {
        if (!open) return
        listOf(0.5, 0.72, 0.85, 1.0).forEach { v ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(v) }
                    .padding(start = 36.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Text(sizeLabel(v), fontSize = 12.sp)
                if (kotlin.math.abs(currentScale - v) < 0.001) {
                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(16.dp))
                }
            }
        }
    }

    @Composable
    private fun AnimHub(onBack: () -> Unit, onPick: (String) -> Unit) {
        val all = engine.idles + engine.turns + engine.moves + engine.clicks + engine.acts
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("◀ 返回", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text("动画集（点击播放）", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            val grouped = all.groupBy { name ->
                when (name) {
                    in engine.idles -> "待机"
                    in engine.turns -> "转向"
                    in engine.moves -> "移动"
                    in engine.clicks -> "点击回应"
                    in engine.acts -> "随机动作"
                    else -> "其他"
                }
            }
            for ((group, names) in grouped) {
                MenuGroup(group)
                names.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(name) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(name, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickLaunchList(cfg: PetConfig, onBack: () -> Unit, onPick: (String) -> Unit) {
        var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        androidx.compose.runtime.LaunchedEffect(Unit) { apps = cfg.quickLaunch() }
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("◀ 返回", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Text("快捷启动", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            if (apps.isEmpty()) {
                Text("（在桌宠设置中添加应用）", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            }
            apps.forEach { (pkg, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(pkg) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(label, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }

    private fun sizeLabel(scale: Double): String = when (scale) {
        0.5 -> "小 (320dp)"
        0.72 -> "中 (462dp)"
        0.85 -> "大 (544dp)"
        else -> "${"%.0f".format(scale * 640)}dp"
    }
}
