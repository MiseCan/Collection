@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
package com.collection.ui

import androidx.compose.ui.res.stringResource
import com.collection.R

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collection.domain.AppearanceSettings
import com.collection.domain.PageTransitionStyle
import com.collection.domain.ThemeMode
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import kotlin.math.roundToInt

private data class SeedColor(val value: Long, val label: String)

private val SeedColors = listOf(
    SeedColor(0xFF006B5E, "Collection绿"),
    SeedColor(0xFFF29E4C, "日落橙"),
    SeedColor(0xFFE85D75, "樱花粉"),
    SeedColor(0xFF7C4DFF, "薰衣草"),
)

@Composable
fun ThemeSettingsScreen(
    appearance: AppearanceSettings,
    onBack: () -> Unit,
    onAppearanceChange: (AppearanceSettings) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = when (appearance.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val style = runCatching { PaletteStyle.valueOf(appearance.paletteStyle) }
        .getOrDefault(PaletteStyle.TonalSpot)
    val spec = runCatching { ColorSpec.SpecVersion.valueOf(appearance.colorSpec) }
        .getOrDefault(ColorSpec.SpecVersion.SPEC_2025)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.surfaceContainerLow,
        topBar = { CollectionTopBar("主题设置", onBack, scrollBehavior, modifier = Modifier.testTag("theme_app_bar")) },
    ) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp,
            bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ThemePreviewCard(appearance) }
        item { ColorSwatchRow(appearance, dark, style, spec, onAppearanceChange) }
        item { ThemeModeRow(appearance, onAppearanceChange) }
        item {
            DropdownSettingCard(
                icon = Icons.Default.Palette,
                title = "色彩风格",
                value = appearance.paletteStyle,
                options = PaletteStyle.entries.map { it.name },
                onSelect = { onAppearanceChange(appearance.copy(paletteStyle = it, dynamicColor = false)) },
            )
        }
        item {
            DropdownSettingCard(
                icon = Icons.Default.Tune,
                title = "色彩标准",
                value = appearance.colorSpec,
                options = ColorSpec.SpecVersion.entries.map { it.name },
                onSelect = { onAppearanceChange(appearance.copy(colorSpec = it, dynamicColor = false)) },
            )
        }
        item {
            SwitchSettingCard(
                icon = Icons.Default.Wallpaper,
                title = "跟随壁纸颜色",
                summary = "从系统壁纸提取配色",
                checked = appearance.dynamicColor,
                onCheckedChange = { onAppearanceChange(appearance.copy(dynamicColor = it)) },
            )
        }
        item {
            SwitchSettingCard(icon = Icons.Default.Palette, title = "动态色彩图标",
                summary = "普通桌面图标跟随壁纸；主题图标由桌面设置控制",
                checked = appearance.dynamicIcon,
                onCheckedChange = { onAppearanceChange(appearance.copy(dynamicIcon = it)) })
        }
        item {
            SwitchSettingCard(
                icon = Icons.AutoMirrored.Filled.MenuOpen,
                title = "预测性返回手势",
                summary = "关闭后仍可返回，但不再实时预览返回结果",
                checked = appearance.predictiveBack,
                onCheckedChange = { onAppearanceChange(appearance.copy(predictiveBack = it)) },
            )
        }
        item {
            SwitchSettingCard(
                icon = Icons.Default.Swipe,
                title = "横滑返回手势",
                summary = "允许横向拖动页面返回上一级",
                checked = appearance.edgeAwareBack,
                onCheckedChange = { onAppearanceChange(appearance.copy(edgeAwareBack = it)) },
            )
        }
        item {
            DropdownSettingCard(
                icon = Icons.Default.ViewCarousel,
                title = "页面切换手势",
                value = when (appearance.pageTransition) {
                    PageTransitionStyle.MIUIX -> "Miuix（iOS 风格）"
                    PageTransitionStyle.FADE -> "淡入淡出"
                },
                options = listOf("Miuix（iOS 风格）", "淡入淡出"),
                onSelect = { value ->
                    onAppearanceChange(
                        appearance.copy(
                            pageTransition = if (value.startsWith("Miuix")) PageTransitionStyle.MIUIX
                            else PageTransitionStyle.FADE,
                        ),
                    )
                },
            )
        }
        item {
            SliderSettingCard(
                icon = Icons.Default.AspectRatio,
                title = "界面缩放",
                summary = "调整全局显示比例",
                valueText = "${(appearance.uiScale * 100).roundToInt()}%",
                value = appearance.uiScale,
                range = 0.85f..1.15f,
                steps = 5,
                onValueChangeFinished = { onAppearanceChange(appearance.copy(uiScale = it)) },
            )
        }
        item {
            Text(
                "主题设置会立即生效并保存在本机。MaterialKolor 负责从种子色生成完整的 Material 3 配色。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
    }
}

@Composable
private fun ThemePreviewCard(appearance: AppearanceSettings) {
    val colors = MaterialTheme.colorScheme
    val window = LocalWindowInfo.current.containerSize
    val ratio = if (window.height > 0) window.width.toFloat() / window.height.toFloat() else 0.46f
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.width(178.dp).aspectRatio(ratio.coerceIn(0.38f, 0.58f)),
            shape = RoundedCornerShape(26.dp),
            color = colors.surfaceContainerHigh,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 1.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(R.string.app_name),
                    modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 8.dp),
                    fontSize = 12.sp,
                    color = colors.onSurface,
                )
                Box(
                    Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(7.dp)).background(colors.primaryContainer),
                )
                Column(
                    Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceContainerHighest),
                    )
                    Box(
                        Modifier.fillMaxWidth().height(13.dp).clip(RoundedCornerShape(5.dp))
                            .background(colors.surfaceContainer),
                    )
                    Box(
                        Modifier.fillMaxWidth().height(13.dp).clip(RoundedCornerShape(5.dp))
                            .background(colors.surfaceContainer),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(40.dp).background(colors.surfaceContainerHigh)
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) { index ->
                        Box(
                            Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(
                                if (index == 0) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatchRow(
    appearance: AppearanceSettings,
    dark: Boolean,
    style: PaletteStyle,
    spec: ColorSpec.SpecVersion,
    onAppearanceChange: (AppearanceSettings) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeedColors.forEachIndexed { index, seed ->
            val scheme = rememberDynamicColorScheme(
                seedColor = Color(seed.value),
                isDark = dark,
                style = style,
                specVersion = spec,
            )
            val selected = !appearance.dynamicColor && appearance.seedColor == seed.value
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(62.dp)
                        .testTag("seed_color_$index")
                        .semantics { contentDescription = seed.label }
                        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .clickable {
                            onAppearanceChange(appearance.copy(seedColor = seed.value, dynamicColor = false))
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(scheme.primary)
                        drawArc(
                            color = scheme.secondary,
                            startAngle = 0f,
                            sweepAngle = 180f,
                            useCenter = true,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            seed.label,
                            tint = scheme.onPrimary,
                            modifier = Modifier.align(Alignment.Center).size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModeRow(
    appearance: AppearanceSettings,
    onAppearanceChange: (AppearanceSettings) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            val selected = appearance.themeMode == mode
            val container by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                CollectionMotionScheme.fastEffectsSpec(), label = "theme_mode_container")
            val foreground by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                CollectionMotionScheme.fastEffectsSpec(), label = "theme_mode_foreground")
            Surface(
                onClick = { onAppearanceChange(appearance.copy(themeMode = mode)) },
                shape = RoundedCornerShape(16.dp),
                color = container, contentColor = foreground,
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        when (mode) {
                            ThemeMode.LIGHT -> Icons.Default.LightMode
                            ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            ThemeMode.DARK -> Icons.Default.DarkMode
                            ThemeMode.BLACK -> Icons.Default.Contrast
                        },
                        contentDescription = when (mode) {
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.SYSTEM -> "跟随系统"
                            ThemeMode.DARK -> "深色"
                            ThemeMode.BLACK -> "纯黑"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchSettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ThemeCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun DropdownSettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, CollectionMotionScheme.fastSpatialSpec(), label = "setting_expand_rotation")
    ThemeCard {
        Box {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                MotionSwap(value) { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                Icon(Icons.Default.ExpandMore, "展开", modifier = Modifier.graphicsLayer { rotationZ = rotation }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        trailingIcon = if (option == value) ({ Icon(Icons.Default.Check, null) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSettingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    ThemeCard {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MotionSwap(valueText) { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
            Slider(
                value = current,
                onValueChange = { current = it },
                onValueChangeFinished = { onValueChangeFinished(current) },
                valueRange = range,
                steps = steps,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("theme_ui_scale"),
            )
        }
    }
}

@Composable
private fun ThemeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}
