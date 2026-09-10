package com.dshpet.android.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.dshpet.android.data.PetConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Catppuccin Mocha 主题（暗色专用）。
 *
 * 色值取自官方 Catppuccin 调色板 https://github.com/catppuccin/palette
 * （mocha）。统一包装全部 Compose 根（设置/日志/聊天/悬浮菜单/气泡等），
 * 让 MaterialTheme.colorScheme 在整个应用内一致。
 */
private val DshPetMochaColors = darkColorScheme(
    primary = Color(0xFF89B4FA),       // blue
    onPrimary = Color(0xFF11111B),     // crust
    primaryContainer = Color(0xFF313244), // surface0
    onPrimaryContainer = Color(0xFFCDD6F4), // text
    secondary = Color(0xFFA6E3A1),     // green
    onSecondary = Color(0xFF11111B),   // crust
    secondaryContainer = Color(0xFF313244), // surface0
    onSecondaryContainer = Color(0xFFCDD6F4), // text
    tertiary = Color(0xFFCBA6F7),      // mauve
    onTertiary = Color(0xFF11111B),    // crust
    tertiaryContainer = Color(0xFF313244), // surface0
    onTertiaryContainer = Color(0xFFCDD6F4), // text
    background = Color(0xFF1E1E2E),    // base
    onBackground = Color(0xFFCDD6F4),  // text
    surface = Color(0xFF1E1E2E),       // base
    onSurface = Color(0xFFCDD6F4),     // text
    surfaceVariant = Color(0xFF313244),// surface0
    onSurfaceVariant = Color(0xFFA6ADC8), // subtext0
    surfaceTint = Color(0xFF89B4FA),   // blue
    inverseSurface = Color(0xFFCDD6F4),// text
    inverseOnSurface = Color(0xFF313244), // surface0
    inversePrimary = Color(0xFF313244),// surface0
    error = Color(0xFFF38BA8),         // red
    onError = Color(0xFF11111B),       // crust
    errorContainer = Color(0xFF313244),// surface0
    onErrorContainer = Color(0xFFF38BA8), // red
    outline = Color(0xFF6C7086),       // overlay0
    outlineVariant = Color(0xFF45475A),// surface1
    scrim = Color(0xFF11111B),         // crust
)

/**
 * 全局主题包装：所有 Compose 根（Activity / 悬浮窗）统一使用 Catppuccin Mocha。
 * 若用户在设置里上传了自定义 TTF/OTF 字体（存于 filesDir），则全局应用该字体；
 * 字体文件在 IO 线程异步加载，不阻塞主线程。
 */
@Composable
fun DshPetTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val appCtx = remember { ctx.applicationContext }
    val customFont by remember(appCtx) { PetConfig.get(appCtx) }
        .flowString("custom_font", "").collectAsState(initial = "")

    // TTF 可能较大，IO 线程加载；加载完成前先渲染默认字体
    val fontFamily by produceState<FontFamily?>(null, customFont) {
        value = if (customFont.isBlank()) null
        else withContext(Dispatchers.IO) {
            runCatching {
                val f = File(appCtx.filesDir, customFont)
                FontFamily(Font(Typeface.createFromFile(f)))
            }.getOrNull()
        }
    }
    val typography = remember(customFont, fontFamily) {
        if (fontFamily == null) Typography() else Typography(defaultFontFamily = fontFamily)
    }

    MaterialTheme(
        colorScheme = DshPetMochaColors,
        typography = typography,
        content = content,
    )
}