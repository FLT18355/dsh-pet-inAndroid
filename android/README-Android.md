# dsh-pet 桌宠 · Android 移植版

将桌面端 [dsh-pet-indesktop](https://github.com/MerZlin/dsh-pet-indesktop)（Python + PySide6）
完整移植到 Android 的原生应用：**Kotlin + Jetpack Compose (Material 3)**，透明悬浮窗桌宠可显示在
任意应用之上。

- **最低 Android 9（API 28）**，targetSdk 34
- **APK 内不含 ffmpeg 等任何重型组件** —— 透明视频素材在 GitHub Actions 构建机上一次性预处理
> **维护**：本 Android 镜像由 [FLT18355](https://github.com/FLT18355) 维护（保留原作者与桌面端 MIT 许可）；上游桌面端见 [dsh-pet-indesktop](https://github.com/MerZlin/dsh-pet-indesktop)。
>
> 构建与发布由 **GitHub Actions** 完成（本仓库 `.github/workflows/build-android.yml`）

## 功能对照（桌面端 → Android）

| 桌面端功能 | Android 移植 |
|---|---|
| 透明无边框置顶窗口 | ✅ 悬浮窗前台服务（`TYPE_APPLICATION_OVERLAY`），显示在任意应用之上 |
| 91 段透明 WebM 动画 | ✅ 构建期重编码为「旁路 alpha」h264（左半 RGB + 右半灰度 alpha），运行时 OpenGL shader 合成透明像素；任何设备都能硬解，**体积反而比原 WebM 更小（约 0.5×）** |
| 动画链状态机（30% 待机 / 10% 转向 / 40% 动作 / 20% 移动） | ✅ `PetEngine` 1:1 移植 |
| 转向翻转朝向 + 水平镜像（含文字动画不镜像） | ✅ |
| 屏幕漫游（移动动画前后 2s 不动，位置插值） | ✅ 按 dp 换算，自动钳制屏幕内 |
| 点击 Q 弹 + 点击回应动画 + 音效 | ✅ 点击 = Q 弹挤压 + 随机回应动画 + 音效（默认/鸭子自选） |
| 拖拽 / SHIFT+拖拽 / 锁定位置 | ✅ 拖动跟手；「仅长按可拖动」「锁定位置」开关 |
| 拖动物理（惯性/抛出/重力/反弹/摩擦） | ✅ `PetEngine` 1:1 移植（估速 + 弹簧 + 抛掷积分） |
| 不移动 / 播放速度 / 动画间隔 | ✅ 设置与菜单实时生效 |
| 4 档大小 / 不透明度 10–100% / 左右朝向 | ✅ |
| 系统托盘 | ✅ 前台服务常驻通知（AI 对话 / 退出 / 点击开设置） |
| 右键菜单（新版现代菜单） | ✅ 长按桌宠弹出 MD3 菜单（对话/动画集/速度/大小/音乐/功能页/余额/更新/多开/快捷启动/设置/退出） |
| 自言自语气泡（多风格/定时/图片） | ✅ 气泡悬浮层锚定桌宠上方，5 种风格 |
| AI 对话（OpenAI 兼容 SSE 流式） | ✅ `ChatActivity`：会话管理 + 流式输出 + 测试连接 |
| DeepSeek 余额查询 | ✅ 气泡/设置页显示 |
| 检查更新 | ✅ GitHub Releases API + 气泡提示 |
| 生小肥鱼（多开） | ✅ 多实例悬浮窗服务，位置/朝向隔离，新实例自动错位 |
| 快捷启动 | ✅ 长按菜单启动已选应用 |
| 音乐播放（自传音乐；无歌词） | ✅ 长按菜单「音乐」显示当前曲目 + 左右键切歌；设置-桌宠-「音乐」上传/删除/音量；播放时桌宠一直播「悠闲哼歌」（边缘探头时不播） |
| 开机自启 | ✅ `BOOT_COMPLETED` 接收器 + 设置开关 |
| 窗口透明区域鼠标穿透 | ➖ 触屏设备整窗为点击区（拖拽目标更大，体验更佳） |
| 主动识屏 / Agent 联动（DSH 桥接） | ➖ Windows 专属，无对应场景 |
| 直播捕获兼容模式 | ➖ 无直播场景 |

## Android 专属新增

- **MD3 界面**：设置页（常规/桌宠行为/外观/AI 对话/快捷启动/关于）+ 长按菜单 + 聊天界面全部 Material 3。
- **毛玻璃效果（默认关闭）**：设置中可开启；Android 12+ 用 `RenderEffect` 真模糊，低版本回退半透明。
- **隐藏后台（默认开启）**：设置中可关闭；通过运行时切换两个 launcher `activity-alias`
  （`LauncherNormal` / `LauncherHidden`，后者声明 `android:excludeFromRecents`）实现——
  开启后应用不显示在最近任务列表，且同一时刻只有一个启动图标。
- **忽略电池优化**：一键引导，防止 OEM 杀后台。
- **前台服务常驻通知**：即使打开全屏应用，桌宠依然在最上层陪伴。

> **v2.0.0 新增**
> - **边缘探头**：把桌宠拖到屏幕左/右边缘松手即贴边探头张望（贴哪一侧由此决定，
>   朝屏内看）；探头期间只播放待机动画，其它动画全部禁用，防止位置/朝向错乱；
>   再拖动一下、关闭开关或「回到右下角」都会恢复原位。
> - **点击音效自选**：默认音效（Q 弹）/ 鸭子音效二选一，宠物菜单「功能」面板与
>   设置-桌宠行为均可切换。
> - **宠物菜单美化 + 功能页**：菜单分组美化；「功能」入口点击后进入功能页
>   （左上角「◀ 返回」回主菜单），边缘探头、点击音效自选、
>   拖动物理、锁定位置等开关集中切换。
>
> **v2.1.0 变更**
> - **删除「黄金回旋」**（点击桌宠旋转一圈的功能整体移除，含设置项与菜单入口）。
> - **边缘探头改为拖到屏幕边缘才生效**：开启后不再立即吸附（旧实现会让桌宠
>   大半移出屏幕、看起来像"消失"），只有拖到屏幕左/右边缘松手才探头。
> - **音乐播放（新增，无歌词）**：
>   - 设置-桌宠-「音乐」可上传自己的音乐（SAF 选择，复制到应用内 `filesDir/music`）；
>   - 长按菜单新增「音乐」组：显示当前正在播放的曲目与序号，左右键切换上一首/下一首，
>     点曲名播放/暂停；「音乐列表」可点选任意曲目、查看列表；
>   - 正在播放音乐时，桌宠始终播放 `悠闲哼歌.webm`；**处于边缘探头状态时不播放**
>     （探头只播待机）；停止音乐会回到正常动画链；
>   - 多开的小肥鱼共用同一个播放器与播放列表；曲目下标与音量会记住。
> - **CI 可自定义更新说明**：`workflow_dispatch` 新增 `release_notes` 输入，
>   内容会写进 GitHub Release 正文（置顶「本次更新说明」）。
>
> **v2.1.0H 修复**（应用内版本号仍为 2.1.0，`2.1.0H` 只是发行版 tag / 发行版名）
> - **修「退出桌宠后整机假死」**（最严重）：`PetVideoView` 原先在 GL 线程仍渲染、
>   ExoPlayer 仍向 Surface 出帧时直接 `surfaceTexture.release()` —— 原生层竞态，
>   可能连 SurfaceFlinger 一起打挂，表现为屏幕只剩画面、触摸/电源键全无响应。
>   现在：先置释放标志 → 断开并释放播放器 → 纹理释放改到 GL 线程队列执行（与
>   `onDrawFrame` 串行）；服务侧顺序也改为**先 release 视频、再移除窗口**；
>   退出时先停掉所有定时器，并同步关闭全部子窗口。顺带把 GL 渲染从
>   持续 60fps 改为**按需渲染**（新帧才画），大幅降低平板 GPU 负载。
> - **修「边缘探头只露一半」**：改为只露头——窗口移出屏幕后，再用
>   `translationX/Y` 把视频内容在窗口内平移，使可见框（宽 30%、高 52%）正好落在
>   角色的头/上半身，身体其余部分裁掉；并按 ±12° 旋转，呈"斜着从屏幕边探出头"；
>   贴左缘朝右、贴右缘朝左（都朝屏内）。可见框贴屏幕底部（否则无法只留上半身）。
> - **修「点菜单里的播放列表崩溃」**：ExoPlayer 改为**懒创建 + 全链路
>   runCatching** —— 打开菜单/设置页只列举曲目文件，不构造播放器；构造失败只
>   降级为"无音乐"，不再带崩进程。音乐相关的所有入口都包了异常保护。
> - **修「气泡不跟随桌宠」**：自言自语气泡在桌宠每次移动（自动散步/拖动/抛掷/
>   碰撞）后重新锚定到桌宠正上方（位置未变则跳过，避免每帧 measure）。
> - **移除彩蛋功能（欧鲸鲸图片弹窗）整体删除**：删除 `EasterEggPopup`、长按菜单
>   入口、服务内持有与清理逻辑，并停止把 `assets/big_blue_fat_fish/*`（约 16MB）
>   打进 APK。**顺带消除了一处"多个触屏遮罩窗口盖住屏幕"的风险来源**。
> - **AI 对话修复**：
>   - 全屏对话 / 悬浮对话窗 / 双击快捷气泡原先各建一个 `ChatViewModel`，各持一份
>     会话内存副本，整份写盘会互相覆盖 → 消息"丢/串"。现在改为**进程级共享同一个
>     ViewModel**，且 `ChatRepo` 写入全部在锁内做"读-改-写"、只允许追加消息；
>   - 流式回复时**自动滚到底**（原来只有消息条数变化才滚动，长回复看不到最新内容）；
>   - 发送被拒（空内容/正在等回复）时**不再清空输入框**；
>   - 「停止」现在会真正关闭连接（原来取消后 `resp.close()` 走不到，连接泄漏），
>     且取消不再误报为网络错误；界面关闭时会取消仍在跑的流式请求（不再白耗 token）。

## 安装

1. 在 [Releases](https://github.com/FLT18355/dsh-pet-inAndroid/releases) 或 Actions Artifact
   下载 `dsh-pet-android-*.apk`。
2. 侧载安装（允许「未知来源」）。
3. 首次打开 → 设置 → 授权「悬浮窗权限」→ 打开桌宠开关。
4. 建议开启「忽略电池优化」+「开机自启」，并允许通知（Android 13+）。

## 操作

| 手势 | 效果 |
|---|---|
| 点击 | Q 弹 + 随机点击回应动画 |
| 拖动 | 移动桌宠（可开物理抛掷） |
| 长按 | MD3 菜单 |
| 长按菜单 → 动画集 | 手动播放任意动画 |
| 长按菜单 → 音乐 | 显示当前播放曲目；◀ / ▶ 切上一首/下一首；点曲名播放/暂停 |
| 拖到屏幕左/右边缘松手 | 开启「边缘探头」后：贴边探头张望（只播待机）；再拖动一下即脱离 |

## 开发 / 构建

```bash
# 1) 预处理素材（本机需 ffmpeg；构建机一次性工具，不进 APK）
bash android/scripts/prepare-assets.sh
#    生成 android/app/src/main/assets/pet/（videos + manifest.json + 音效 + 壁纸）

# 2) 编译（需要 Android SDK；本机环境不适合时可交给 GitHub Actions）
cd android && ./gradlew :app:assembleRelease
#    产物：android/app/build/outputs/apk/release/app-release.apk
```

### CI（推荐）

推送 `main` 自动触发 `.github/workflows/build-android.yml`：

1. `compile-check`：快速编译检查（不依赖素材，秒级反馈）。
2. `build`：ffmpeg 预处理素材 → Gradle 构建 → 签名 Release APK → 上传 Artifact；
   推送 `android-*` 标签（或 workflow_dispatch 指定 tag）时自动创建 GitHub Release。
   - workflow_dispatch 的 **`release_notes`** 输入可自定义本次更新说明，内容会写进
     Release 正文（放在默认说明之前）；留空则只用默认说明。

签名：仓库内 `android/keystore/dshpet-release.keystore`（口令 `dshpet123`，见
`android/app/build.gradle.kts`），保证各次构建签名一致，可原地升级。

## 已知差异与限制

- 触屏无右键：`shift_drag` 语义映射为「仅长按可拖动」。
- 素材为构建时重编码产物（不入库），改素材后需重新运行预处理脚本。
- 个别机型后台限制严格，建议开启忽略电池优化；被系统回收后，通知栏或重新打开应用可恢复。
- 聊天背景主题（内置壁纸/自定义图片）暂未移植（保留素材，后续可加）。
- 音乐播放不做歌词（按需求）；音乐文件复制到应用私有目录，卸载应用即一起删除；
  播放器为进程级单例，多开的小肥鱼共用同一首音乐，桌宠全部退出后音乐暂停。
- 桌面端「主动识屏」「Agent 联动」「直播捕获」为 Windows 专属，无对应 Android 场景。

## 目录结构

```
android/
├── app/src/main/
│   ├── java/com/dshpet/android/
│   │   ├── MainActivity.kt          # 设置页（MD3）
│   │   ├── PetApp.kt                # 应用入口/通知渠道/别名同步
│   │   ├── data/PetConfig.kt        # DataStore 设置 + 多开实例状态
│   │   ├── pet/PetOverlayService.kt # 悬浮窗前台服务（手势/多开/通知）
│   │   ├── pet/PetVideoView.kt      # GLSurfaceView + RGB/alpha shader + ExoPlayer
│   │   ├── pet/PetEngine.kt         # 动画链状态机 + 物理（桌面端 1:1 移植）
│   │   ├── pet/PetCatalog.kt        # 动画目录/分类/时长
│   │   ├── pet/PetMenu.kt           # 长按 MD3 菜单（含音乐面板）
│   │   ├── pet/PetMusicPlayer.kt    # 音乐播放器（进程级单例，无歌词）
│   │   ├── pet/SpeechBubble.kt      # 自言自语气泡
│   │   ├── pet/Balance.kt / Updater.kt / BootReceiver.kt
│   │   └── chat/                    # ChatActivity/ViewModel/SSE 客户端/会话存储
│   ├── res/                         # MD3 主题/图标/布局
│   └── assets/pet/                  # 预处理产物（git 忽略，CI 生成）
├── scripts/prepare-assets.sh        # WebM → 旁路 alpha h264 + manifest
├── keystore/                        # Release 签名
└── gradle wrapper / build 文件
```

## 许可证

MIT（与桌面端一致，见仓库根 LICENSE）。
