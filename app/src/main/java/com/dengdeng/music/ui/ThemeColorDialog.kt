package com.dengdeng.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.dengdeng.music.ui.theme.OFFICIAL_PRIMARY_HEX
import com.dengdeng.music.ui.theme.colorToHex
import com.dengdeng.music.ui.theme.parseHexColor

/**
 * 主题色彩选择弹窗
 *
 * 交互约定（2026-09-12 与用户对齐）：
 *  - 顶部：浅色模式 / 深色模式 二选一（不保留"跟随系统"）
 *  - 中部：色板 = 官方浅紫（固定首位）+ 历史主题色（新尝试的往前顶，外部负责持久化顺序）
 *  - 底部：HEX 色号自由输入，输入合法值即时预览
 *  - 任何选择立即【临时预览】；点"确定"才持久化；点右上角 ✕ / 点弹窗外 = 恢复原样
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeColorDialog(
    /** 进入弹窗时的模式（1=浅色 2=深色） */
    initialMode: Int,
    /** 当前持久化的主题色（"" = 官方浅紫） */
    currentColor: String,
    /** 历史主题色（不含官方浅紫，新的在前） */
    history: List<String>,
    /** 临时预览（不落盘）：参数为 模式 + 6位HEX（"" = 官方浅紫） */
    onPreview: (Int, String) -> Unit,
    /** 确认修改（落盘 + 记入历史） */
    onConfirm: (Int, String) -> Unit,
    /** 关闭且不保存（恢复原样由外部回滚预览） */
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(if (initialMode == 2) 2 else 1) }
    var selectedColor by remember { mutableStateOf(currentColor.trim().uppercase()) }
    var hexInput by remember {
        mutableStateOf(currentColor.trim().uppercase().ifEmpty { "" })
    }
    var inputValid by remember { mutableStateOf(true) }

    fun preview(m: Int = mode, c: String = selectedColor) = onPreview(m, c)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("主题色彩", modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭并恢复原样")
                }
            }
        },
        text = {
            Column {
                // ===== 模式 =====
                Text("模式", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == 1,
                        onClick = { mode = 1; preview(m = 1) },
                        label = { Text("浅色模式") }
                    )
                    FilterChip(
                        selected = mode == 2,
                        onClick = { mode = 2; preview(m = 2) },
                        label = { Text("深色模式") }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ===== 主题色色板 =====
                Text("主题色", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 官方浅紫固定第一位
                    ColorDot(
                        hex = OFFICIAL_PRIMARY_HEX,
                        label = "官方浅紫",
                        selected = selectedColor.isEmpty() ||
                                selectedColor == OFFICIAL_PRIMARY_HEX,
                        onClick = {
                            selectedColor = ""
                            hexInput = ""
                            inputValid = true
                            preview(c = "")
                        }
                    )
                    // 历史主题色（新尝试的在前；初次为 6 个常用色）
                    history.forEach { hex ->
                        ColorDot(
                            hex = hex,
                            label = "#$hex",
                            selected = selectedColor == hex,
                            onClick = {
                                selectedColor = hex
                                hexInput = hex
                                inputValid = true
                                preview(c = hex)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ===== 自定义 HEX 输入 =====
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { v ->
                        hexInput = v
                        val parsed = parseHexColor(v)
                        inputValid = v.isBlank() || parsed != null
                        if (parsed != null) {
                            val norm = colorToHex(parsed)
                            selectedColor = norm
                            preview(c = norm)
                        }
                    },
                    label = { Text("自定义色号") },
                    placeholder = { Text("如 1E88E5 或 #1E88E5") },
                    supportingText = {
                        Text(
                            if (inputValid) "可自选 HEX 色号，输入后即时预览"
                            else "格式：6 位 HEX（0-9 / A-F），如 1E88E5",
                            color = if (inputValid) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    },
                    isError = !inputValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            // 大号确认按钮：主题色填充、通栏宽度
            Button(
                onClick = { onConfirm(mode, selectedColor) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("确定", fontSize = 16.sp)
            }
        }
    )
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
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .border(BorderStroke(2.dp, ring), CircleShape)
        )
        Spacer(Modifier.width(0.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
