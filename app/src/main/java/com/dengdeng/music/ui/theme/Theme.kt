package com.dengdeng.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 浅色主题 —— 温暖米白基调，清爽耐看（官方浅紫 #6750A4） */
private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFDF8FD),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFDF8FD),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E)
)

/** 深色主题 —— 深紫黑基调，护眼舒适 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99)
)

/** 官方默认主色（固定首位，不可被历史顶掉） */
val OFFICIAL_PRIMARY_HEX = "6750A4"

/** 常用预设主题色：主色板 7 个空槽位的初始填充；试用新色后逐渐被顶替（旧的进历史色页） */
val DEFAULT_PRESET_HISTORY = listOf(
    "E53935", // 中国红
    "F57C00", // 橙
    "F9A825", // 金黄
    "43A047", // 翡翠绿
    "1E88E5", // 湖蓝
    "EC407A"  // 樱花粉
)

/**
 * 解析 6 位 HEX 色号为 Compose Color。
 * 接受 "RRGGBB" / "#RRGGBB"（也兼容 0x 前缀），非法返回 null。
 */
fun parseHexColor(s: String): Color? {
    val t = s.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (t.length != 6) return null
    if (t.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
    return Color(java.lang.Long.parseLong("FF$t", 16).toInt())
}

/** 把 Color 规整成 6 位大写 HEX（无 #），用于存档比较 */
fun colorToHex(c: Color): String {
    fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt().let { "%02X".format(it) }
    return ch(c.red) + ch(c.green) + ch(c.blue)
}

/** 浅色方案 + 自定义主色：只替换主色系（primary 一族），背景保持中性米白 */
private fun lightSchemeWith(primary: Color): ColorScheme = LightColors.copy(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = lerp(primary, Color.White, 0.82f),
    onPrimaryContainer = lerp(primary, Color.Black, 0.65f)
)

/** 深色方案 + 自定义主色：主色提亮保证在深底上的可读性 */
private fun darkSchemeWith(primary: Color): ColorScheme = DarkColors.copy(
    primary = lerp(primary, Color.White, 0.45f),
    onPrimary = lerp(primary, Color.Black, 0.72f),
    primaryContainer = lerp(primary, Color.Black, 0.35f),
    onPrimaryContainer = lerp(primary, Color.White, 0.88f)
)

@Composable
fun MusicAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 自定义主题色（null = 官方浅紫）；只替换主色系，不动中性背景 */
    primaryColor: Color? = null,
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) {
        primaryColor?.let { darkSchemeWith(it) } ?: DarkColors
    } else {
        primaryColor?.let { lightSchemeWith(it) } ?: LightColors
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
