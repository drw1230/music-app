package com.dengdeng.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dengdeng.music.ui.theme.DEFAULT_PRESET_HISTORY
import com.dengdeng.music.ui.theme.OFFICIAL_PRIMARY_HEX
import com.dengdeng.music.ui.theme.colorToHex
import com.dengdeng.music.ui.theme.parseHexColor
import kotlin.random.Random

/**
 * 主题色彩弹窗（2026-09-12 二版：点击即生效，无确定/无回滚）
 *
 *  - 顶部：浅色模式 / 深色模式 二选一，点了立即换
 *  - 主色板：固定 8 个位置 = 官方浅紫（永不被顶）+ 7 个槽位（试用新色往前顶，旧色存进历史色页）
 *  - "历史色"入口在"主题色"标题行最右：进去看所有用过的色号球，点击应用
 *  - 底部：随机换色工具行 = 3 个随机色球（点击应用）+ "随机色"按钮（重新生成 3 个）
 *  - HEX 输入：输入合法 6 位色号即时生效
 *  - ✕ 仅关闭弹窗（主题保持最后一次选择）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeColorDialog(
    /** 进入弹窗时的模式（0=跟随系统 1=浅色 2=深色） */
    initialMode: Int,
    /** 当前主题色（"" = 官方浅紫） */
    currentColor: String,
    /** 历史主题色（新在前，不含官方浅紫，可能为空） */
    history: List<String>,
    /** 点击即生效（外部落盘 + 记历史） */
    onApply: (Int, String) -> Unit,
    /** 仅关闭弹窗 */
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(if (initialMode in 1..2) initialMode else 0) }
    var selectedColor by remember { mutableStateOf(currentColor.trim().uppercase()) }
    var hexInput by remember { mutableStateOf(currentColor.trim().uppercase()) }
    var inputValid by remember { mutableStateOf(true) }
    var showHistory by remember { mutableStateOf(false) }
    // 底部 3 个随机色球（初始就生成一批；点"随机色"重新生成；点球才应用）
    var randomBalls by remember { mutableStateOf(List(3) { randomHex() }) }

    fun apply(m: Int = mode, c: String = selectedColor) = onApply(m, c)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("主题色彩", modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        },
        text = {
            Column {
                // ===== 模式（跟随系统 / 浅色 / 深色） =====
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == 0,
                        onClick = { mode = 0; apply(m = 0) },
                        label = { Text("跟随系统") }
                    )
                    FilterChip(
                        selected = mode == 1,
                        onClick = { mode = 1; apply(m = 1) },
                        label = { Text("浅色模式") }
                    )
                    FilterChip(
                        selected = mode == 2,
                        onClick = { mode = 2; apply(m = 2) },
                        label = { Text("深色模式") }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ===== 主题色 / 历史色 标题行 =====
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showHistory) "历史色（点击应用）" else "主题色",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (showHistory) "返回" else "历史色",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showHistory = !showHistory }
                    )
                }
                Spacer(Modifier.height(8.dp))

                // ===== 色板 / 历史色列表 =====
                if (showHistory) {
                    // 历史色页：所有试用过的颜色，新的在前
                    if (history.isEmpty()) {
                        Text(
                            "还没有历史色，试用几个颜色后这里就有了",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            history.forEach { hex ->
                                ColorDot(
                                    hex = hex,
                                    label = "#$hex",
                                    selected = selectedColor == hex,
                                    onClick = {
                                        selectedColor = hex
                                        hexInput = hex
                                        inputValid = true
                                        apply(c = hex)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // 主色板：官方浅紫固定第一位 + 7 个槽位（试用过的优先，空槽用预设色填充）
                    val tried = history
                    val slots = (tried + DEFAULT_PRESET_HISTORY.filter { p ->
                        tried.none { it.equals(p, ignoreCase = true) }
                    }).take(7)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ColorDot(
                            hex = OFFICIAL_PRIMARY_HEX,
                            label = "官方浅紫",
                            selected = selectedColor.isEmpty() ||
                                    selectedColor == OFFICIAL_PRIMARY_HEX,
                            onClick = {
                                selectedColor = ""
                                hexInput = ""
                                inputValid = true
                                apply(c = "")
                            }
                        )
                        slots.forEach { hex ->
                            ColorDot(
                                hex = hex,
                                label = "#$hex",
                                selected = selectedColor == hex,
                                onClick = {
                                    selectedColor = hex
                                    hexInput = hex
                                    inputValid = true
                                    apply(c = hex)
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ===== 自定义 HEX 输入（即时生效） =====
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { v ->
                        hexInput = v
                        val parsed = parseHexColor(v)
                        inputValid = v.isBlank() || parsed != null
                        if (parsed != null) {
                            val norm = colorToHex(parsed)
                            selectedColor = norm
                            apply(c = norm)
                        }
                    },
                    label = { Text("自定义色号") },
                    placeholder = { Text("如 1E88E5 或 #1E88E5") },
                    supportingText = {
                        Text(
                            if (inputValid) "可自选 HEX 色号，输入即生效"
                            else "格式：6 位 HEX（0-9 / A-F），如 1E88E5",
                            color = if (inputValid) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    },
                    isError = !inputValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))

                // ===== 随机换色工具行：3 个随机色球 + "随机色"按钮 =====
                Row(verticalAlignment = Alignment.CenterVertically) {
                    randomBalls.forEach { hex ->
                        RandomBall(
                            hex = hex,
                            selected = selectedColor == hex,
                            onClick = {
                                selectedColor = hex
                                hexInput = hex
                                inputValid = true
                                apply(c = hex)
                            }
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { randomBalls = List(3) { randomHex() } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("随机色")
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/** 随机生成一个"耐看"的色号：随机色相 + 适中饱和度/明度，保证浅深主题下都不刺眼 */
private fun randomHex(): String {
    val hsv = floatArrayOf(
        Random.nextFloat() * 360f,
        0.50f + Random.nextFloat() * 0.25f,   // 饱和度 0.50~0.75
        0.48f + Random.nextFloat() * 0.17f    // 明度 0.48~0.65
    )
    val c = android.graphics.Color.HSVToColor(hsv)
    return "%06X".format(c and 0xFFFFFF)
}

/** 色板里的一个色点：圆形色块 + 下方色号文字，选中态加主色描边 */
@Composable
private fun ColorDot(hex: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = parseHexColor(hex) ?: Color(0xFF6750A4)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .border(BorderStroke(2.dp, ring), CircleShape)
        )
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 底部随机色球：比色板色点稍大，带色号，点击应用 */
@Composable
private fun RandomBall(hex: String, selected: Boolean, onClick: () -> Unit) {
    val color = parseHexColor(hex) ?: Color.Gray
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(color)
                .border(BorderStroke(2.dp, ring), CircleShape)
        )
        Text(
            "#$hex",
            fontSize = 10.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
