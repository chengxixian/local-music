package com.liquidmiuix.theme

import android.app.Activity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 主题层。
 *
 * 与 miuix 自身的做法保持一致：
 *
 *  - **颜色一律取 miuix 语义色**（`MiuixTheme.colorScheme.*`），不写死十六进制。
 *    miuix 的 `surface` 是页面底色、`surfaceContainer` 是卡片底色 ——
 *    两者天然差一档，这正好满足玻璃"背后必须有可被糊掉的结构"的前提。
 *  - miuix 的 [Colors] **映射**成 Material3 [ColorScheme]，
 *    让少数必须用 Material3 的组件（Dialog / BottomSheet / OutlinedTextField）
 *    与 miuix 组件共用同一套语义色，避免"对话框是 Material 紫、页面是 MIUI 蓝"。
 *  - 用 [MaterialExpressiveTheme] + `MotionScheme.expressive()`，
 *    这是那种「Q 弹」手感的来源。
 */

/** MIUI 默认蓝。miuix 自身 `lightColorScheme()` 的 primary 就是它。 */
val MiuixBluePrimary = Color(0xFF3482FF)
private val MiuixBluePressed = Color(0xFF2E6BE6)
private val MiuixBlueHighlight = Color(0xFF5B9DFF)

/** 交互态强调色。 */
object LiquidAccent {
    val pressed = MiuixBluePressed
    val highlight = MiuixBlueHighlight
}

/**
 * 全局圆角尺度。与 [LiquidShapes] 同源，供需要直接构造 `RoundedCornerShape` 的场景使用
 * （玻璃层、局部容器），避免散落魔法值。
 */
object LiquidRadii {
    val small = 12.dp
    val medium = 16.dp
    val large = 24.dp
    val extraLarge = 32.dp
}

/**
 * 全局间距尺度。命名按用途而非数值，调整时只改这一处。
 */
object LiquidSpacing {
    /** 紧邻元素，如图标与其文字标签。 */
    val tight = 4.dp

    /** 行内元素间距。 */
    val inline = 8.dp

    /** 图标与文字块之间（列表项用它）。 */
    val leading = 13.dp

    /** 相关内容块之间。 */
    val item = 14.dp

    /** 卡片内部留白。 */
    val card = 16.dp

    /** 页面左右安全边距。 */
    val page = 20.dp
}

private val LiquidShapes = Shapes(
    small = RoundedCornerShape(LiquidRadii.small),
    medium = RoundedCornerShape(LiquidRadii.medium),
    large = RoundedCornerShape(LiquidRadii.large),
    extraLarge = RoundedCornerShape(LiquidRadii.extraLarge),
)

/**
 * 排版尺度。
 *
 * 把各处散落的 10/12/14/15/16/18/20sp 收敛成一套固定阶梯，
 * 界面协调度的问题基本都出在这里。以下沿用同一套数值。
 */
private val LiquidTypography = Typography(
    displaySmall = TextStyle(fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Light),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
)

/**
 * 把 miuix 的 [Colors] 映射成 Material3 [ColorScheme]。
 *
 * 为什么需要：项目中仍有必须用 Material3 的组件（弹窗、输入框、Card），
 * 让它们与 miuix 组件共用同一套语义色，才不会出现割裂的观感。
 *
 * 注意 miuix 的 [Colors] 没有 `tertiary`（只有 `tertiaryContainer`），
 * 也没有 `outlineVariant`（对应的是 `dividerLine`）。属性名已按实际 API 对齐。
 */
private fun Colors.toMaterialScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiaryContainer,
        onTertiary = onTertiaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariantSummary,
        surfaceTint = primary,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = dividerLine,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}

/**
 * 强调色。
 *
 * [Dynamic] 是**默认**：走 Monet，配色直接从**壁纸**取（Android 12+ 的
 * `android.R.color.system_accent1_500`）。换壁纸 → 全局主色跟着变，
 * 这就是「动态取色」。其余几项是固定色，供用户在设置里锁定。
 */
enum class LiquidAccentColor(val label: String, private val seed: Long?) {
    /** 跟随壁纸（Monet）。 */
    Dynamic("跟随壁纸", null),
    /** MIUI 默认蓝，与 miuix 内置 primary 完全一致。 */
    MiuBlue("MIUI 蓝", 0xFF3482FF),
    Blue("蓝", 0xFF415F91),
    Violet("紫", 0xFF6750A4),
    Green("绿", 0xFF356A35),
    Orange("橙", 0xFF8B4F23),
    ;

    /** 固定色的种子；[Dynamic] 返回 null（由系统提供）。 */
    fun seedColor(): androidx.compose.ui.graphics.Color? =
        seed?.let { androidx.compose.ui.graphics.Color(it) }

    companion object {
        val Default = Dynamic

        fun fromId(id: String?): LiquidAccentColor =
            entries.firstOrNull { it.name == id } ?: Default
    }
}

/**
 * 应用主题。
 *
 * ## 动态取色（Monet）
 *
 * 默认 [LiquidAccentColor.Dynamic]：`colorSchemeMode` 取 `MonetSystem`，
 * 让 miuix 用**系统壁纸**生成的配色。这台机器上实测的效果就是
 * 「换个壁纸，全应用主色跟着换」。
 *
 * [LiquidAccentColor.MiuBlue] 是特例：它要的是**原生 MIUI 蓝**，
 * 所以走非 Monet 模式并让 miuix 用它内置的配色（primary 即 `#3482FF`）——
 * 走 Monet 会被系统取色改写成近似色，就不是那个蓝了。
 *
 * ## 为什么 surface 保持中性
 *
 * `paletteStyle = Neutral` 让 surface / background 不被强调色染色，
 * 主色的作用范围收敛到 primary 系（按钮、开关、选中态）。
 * 这样「换主题色 = 换按钮颜色」，页面底色始终干净 —— 玻璃也才有稳定的采样底。
 *
 * @param accentColor 强调色来源，默认跟随壁纸。
 * @param darkTheme 为 null 时跟随系统。
 */
@Composable
fun LiquidTheme(
    accentColor: LiquidAccentColor = LiquidAccentColor.Default,
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    val context = LocalContext.current

    // Monet 与非 Monet 是两套模式，深浅三态与之正交。
    val mode = if (accentColor == LiquidAccentColor.MiuBlue) {
        if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light
    } else {
        if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight
    }

    // Dynamic 交给系统（keyColor = null）；固定色用枚举里的种子。
    // MiuBlue 也用 null —— 走的是 miuix 内置配色，不需要种子。
    val keyColor = if (accentColor == LiquidAccentColor.Dynamic || accentColor == LiquidAccentColor.MiuBlue) {
        null
    } else {
        accentColor.seedColor()
    }

    val controller = remember(accentColor, dark) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = keyColor,
            // Neutral：surface / background 保持中性，不被强调色染色。
            paletteStyle = ThemePaletteStyle.Neutral,
            isDark = dark,
        )
    }

    // 状态栏 / 导航栏图标明暗跟随主题，浅色主题下才是深色图标。
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MiuixTheme(controller = controller) {
        // 刻意不用 remember：miuix 会复用同一个 Colors 实例并原地更新其属性，
        // 把实例当 key 永远不会失效，会导致换强调色后 Material3 配色不刷新。
        // 每次重组重算只是一次色值搬运，成本可忽略。
        val miuixScheme = MiuixTheme.colorScheme
        MaterialTheme(
            colorScheme = miuixScheme.toMaterialScheme(dark),
            typography = LiquidTypography,
            shapes = LiquidShapes,
            content = content,
        )
    }
}

/**
 * 动效令牌。
 *
 * 核心原则：**一律用低刚度弹簧，不用线性 tween**。
 * 低刚度让动画有"跟手 + 回弹"的尾韵，MediumBouncy 提供一次可感知的过冲 ——
 * 这就是那种"Q 弹"手感的来源。
 *
 * 用泛型函数而非共享 val：`SpringSpec<T>` 的目标类型要由调用处推断，
 * 写成 `val bouncy = spring<Float>(...)` 会让 Dp/Color/Offset 动画无法复用同一令牌。
 */
object LiquidMotion {
    /** 主交互：低刚度 + 中等回弹。按钮、卡片按压、页面切换。 */
    fun <T> bouncy(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** 大幅位移 / 需要柔和收尾（页面推入、浮入）。 */
    fun <T> gentle(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessVeryLow,
    )

    /** 高频小位移：选中指示器位移、淡入淡出。 */
    fun <T> snappy(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * 点按反馈专用。
     *
     * 不能直接用 [bouncy]：StiffnessLow 是为"大位移 + 慢收尾"调的，
     * 用在 0.14 量级的按压缩放上，按下那一下要等约 150ms 才看得出压下去，
     * 会觉得"点了没反应"。StiffnessMedium 让按压当帧就跟手，
     * 阻尼仍是 MediumBouncy，所以松手依然有一次可感知的回弹。
     */
    fun <T> press(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** 列表错峰入场的基础步长。 */
    const val StaggerMillis = 40L

    /** 按压缩放的下限，越小压得越深。 */
    const val PressedScale = 0.96f
}
