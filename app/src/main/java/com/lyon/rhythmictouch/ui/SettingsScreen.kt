package com.lyon.rhythmictouch.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    store: ConfigStore,
    onMonetChange: (Boolean) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val config = remember { mutableStateOf(store.read()) }
    var isRestarting by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    if (showAbout) {
        AboutScreen(
            onBack = { showAbout = false },
            monetEnabled = config.value.monet,
        )
        return
    }

    suspend fun restartSystemUI() {
        isRestarting = true
        try {
            withContext(Dispatchers.IO) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am crash com.android.systemui"))
                process.waitFor()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            kotlinx.coroutines.delay(2000)
            isRestarting = false
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "设置")
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SuperSwitch(
                checked = config.value.enabled,
                onCheckedChange = { checked ->
                    config.value = config.value.copy(enabled = checked)
                    store.write(config.value)
                    context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                },
                title = "音律触感",
                summary = "从系统全局音频流中捕捉音乐节奏并触发振动，可能增加功耗与系统占用",
            )

            SuperSwitch(
                checked = config.value.whitelistMode,
                onCheckedChange = { checked ->
                    config.value = config.value.copy(whitelistMode = checked)
                    store.write(config.value)
                    context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                },
                title = "作用域白名单模式",
                summary = if (config.value.whitelistMode) {
                    "白名单：仅作用域中的应用触发振动"
                } else {
                    "黑名单：作用域中的应用不触发振动"
                },
            )

            SuperSwitch(
                checked = config.value.monet,
                onCheckedChange = { checked ->
                    config.value = config.value.copy(monet = checked)
                    store.write(config.value)
                    onMonetChange(checked)
                },
                title = "全局莫奈取色",
                summary = "使用系统 Material You 动态取色",
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "振动强度",
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = "${config.value.intensity}%",
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = config.value.intensity.toFloat(),
                        onValueChange = { value ->
                            config.value = config.value.copy(intensity = value.toInt())
                            store.write(config.value)
                        },
                        valueRange = 0f..100f,
                        onValueChangeFinished = {
                            context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                        },
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "振动延迟",
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = "${config.value.vibrationDelay}ms",
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = "调整跟随振动的延迟，部分蓝牙耳机有延迟时可设置以对齐节拍",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 12.sp,
                    )
                    Slider(
                        value = config.value.vibrationDelay.toFloat(),
                        onValueChange = { value ->
                            config.value = config.value.copy(vibrationDelay = value.toInt())
                            store.write(config.value)
                        },
                        valueRange = 0f..300f,
                        onValueChangeFinished = {
                            context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                        },
                    )
                }
            }

            SmallTitle("日志")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "日志输出模式",
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                    Text(
                        text = when (config.value.logMode) {
                            RhythmicConstants.LOG_MODE_VIBRATE -> "仅在发生振动时输出日志"
                            RhythmicConstants.LOG_MODE_NONE -> "不输出任何日志"
                            else -> "输出全部调试日志"
                        },
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val options = listOf(
                            RhythmicConstants.LOG_MODE_ALL to "全部输出",
                            RhythmicConstants.LOG_MODE_VIBRATE to "震动时输出",
                            RhythmicConstants.LOG_MODE_NONE to "不输出",
                        )
                        options.forEach { (mode, label) ->
                            val selected = config.value.logMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.surfaceContainerHighest
                                    )
                                    .clickable {
                                        config.value = config.value.copy(logMode = mode)
                                        store.write(config.value)
                                        context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Color.White
                                    else MiuixTheme.colorScheme.onSurfaceContainer,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }

            SmallTitle("系统")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isRestarting) {
                            GlobalScope.launch { restartSystemUI() }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "重启SystemUI",
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = if (isRestarting) "正在重启 SystemUI..." else "仅在配置不生效时尝试，应用大部分配置都是实时生效的",
                            color = if (isRestarting) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAbout = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "关于",
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = "音律触感",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }
        }
    }
}
