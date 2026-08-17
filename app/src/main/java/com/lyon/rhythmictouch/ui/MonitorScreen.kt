package com.lyon.rhythmictouch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.pow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.lyon.rhythmictouch.LiveState
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.systemui.SpectrumBand
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.RecordingTape
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.VolumeOff
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MonitorScreen(
    bottomBar: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "RhythmicTouch",
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SmallTitle("音频检测")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column {
                    LiveIndicator()
                }
            }
        }
    }
}

@Composable
private fun LiveIndicator() {
    var level by remember { mutableFloatStateOf(0f) }
    var bands by remember { mutableStateOf(emptyList<SpectrumBand>()) }
    var beat by remember { mutableStateOf(false) }
    var peakBandIndex by remember { mutableIntStateOf(0) }
    var blocked by remember { mutableStateOf(false) }
    var activeApp by remember { mutableStateOf<String?>(null) }
    var engineActive by remember { mutableStateOf(false) }
    var vibrationMode by remember { mutableStateOf("安静") }
    val defaultBands = defaultBandsCache

    LaunchedEffect(Unit) {
        while (true) {
            val newLevel = LiveState.level
            if (newLevel != level) level = newLevel
            val newBands = LiveState.bands
            if (newBands != bands) bands = newBands
            val newBeat = LiveState.beat
            if (newBeat != beat) beat = newBeat
            val newPeak = LiveState.peakBandIndex
            if (newPeak != peakBandIndex) peakBandIndex = newPeak
            val newBlocked = LiveState.blocked
            if (newBlocked != blocked) blocked = newBlocked
            val newActiveApp = LiveState.activeApp
            if (newActiveApp != activeApp) activeApp = newActiveApp
            val newEngineActive = LiveState.engineActive && LiveState.isFresh()
            if (newEngineActive != engineActive) engineActive = newEngineActive
            val newMode = LiveState.vibrationMode
            if (newMode != vibrationMode) vibrationMode = newMode
            kotlinx.coroutines.delay(80)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    !engineActive -> "模块未运行"
                    blocked -> "已屏蔽振动"
                    else -> "检测中（${bands.size} 频段）"
                },
                color = when {
                    !engineActive -> MiuixTheme.colorScheme.onBackgroundVariant
                    blocked -> MiuixTheme.colorScheme.error
                    else -> MiuixTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = if (bands.isNotEmpty()) {
                    val topBand = bands.maxByOrNull { it.value }
                    if (topBand != null && topBand.value > 0.1f) {
                        "峰值：#${topBand.index} [${topBand.freqStart.toInt()}-${topBand.freqEnd.toInt()}Hz]"
                    } else {
                        "安静"
                    }
                } else {
                    "无数据"
                },
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Spacer(Modifier.height(8.dp))

        LevelBar(level = level, beat = beat)

        Spacer(Modifier.height(12.dp))

        val displayBands = if (bands.isEmpty()) {
            defaultBands
        } else {
            bands
        }
        
        SpectrumGrid(bands = displayBands, peakBandIndex = peakBandIndex)
        
        Spacer(Modifier.height(8.dp))

        Text(
            text = "活跃频段",
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(6.dp))

        val activeBands = displayBands
            .filter { it.value > 0.25f }
            .sortedByDescending { it.value }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (activeBands.isEmpty()) {
                Text(
                    text = "无",
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    fontSize = 12.sp,
                )
            } else {
                for (band in activeBands) {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "#${band.index} ${"%.0f".format(band.value * 100)}%",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        
        val context = LocalContext.current
        VibrationModePanel(
            currentMode = vibrationMode,
            onTest = { modeKey ->
                context.sendBroadcast(
                    Intent(RhythmicConstants.ACTION_TEST_VIBRATION)
                        .setPackage(RhythmicConstants.SYSTEMUI_PACKAGE)
                        .putExtra(RhythmicConstants.EXTRA_TEST_MODE_KEY, modeKey),
                )
            },
        )

        Spacer(Modifier.height(10.dp))

        val appLabel = appLabel(activeApp)
        Text(
            text = if (appLabel != null) "当前播放: $appLabel" else "当前无应用在播放",
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
        )
    }
}

private data class ModeItem(
    val emoji: String,
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val modeKey: String?,
)

@Composable
private fun VibrationModePanel(
    currentMode: String,
    onTest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vibrationModes = listOf(
        ModeItem("💥", MiuixIcons.Music, "重长振", "强低音节拍", "heavyLong"),
        ModeItem("💢", MiuixIcons.Play, "重短振", "中频重击", "heavyShort"),
        ModeItem("⚡", MiuixIcons.Timer, "中等击打", "普通节拍", "mediumHit"),
        ModeItem("🔊", MiuixIcons.VolumeUp, "长脉动", "持续低频", "longPulse"),
        ModeItem("🎵", MiuixIcons.RecordingTape, "中敲击", "中频突出", "midTap"),
        ModeItem("🎶", MiuixIcons.Alarm, "上升轻击", "音符起始", "risingTap"),
        ModeItem("✨", MiuixIcons.Tune, "柔和细节", "轻微细节", "softTick"),
        ModeItem("😴", MiuixIcons.VolumeOff, "安静", "无振动", null),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "振动模式监控",
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(6.dp))

        val rows = vibrationModes.chunked(4)

        for (rowModes in rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (mode in rowModes) {
                    ModeBox(
                        mode = mode,
                        isActive = "${mode.emoji} ${mode.title}" == currentMode,
                        onTest = onTest,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (rowModes.size < 4) {
                    repeat(4 - rowModes.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (rowModes != rows.last()) {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ModeBox(
    mode: ModeItem,
    isActive: Boolean,
    onTest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }

    fun trigger() {
        mode.modeKey?.let { onTest(it) }
        scope.launch {
            scale.snapTo(1f)
            scale.animateTo(1.2f, tween(60))
            scale.animateTo(1f, tween(160))
        }
    }

    val activeHighlight = mode.modeKey != null && isActive
    val quietActive = mode.modeKey == null && isActive

    val bgColor = when {
        activeHighlight -> MiuixTheme.colorScheme.primary
        quietActive -> MiuixTheme.colorScheme.sliderBackground
        else -> MiuixTheme.colorScheme.surfaceContainerHighest
    }

    Column(
        modifier = modifier
            .height(70.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { trigger() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = mode.icon,
            contentDescription = mode.title,
            tint = if (activeHighlight) Color.White else MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = mode.title,
            fontSize = 11.sp,
            color = if (activeHighlight) Color.White else MiuixTheme.colorScheme.onSurfaceContainerVariant,
            maxLines = 1,
        )
        Text(
            text = mode.desc,
            fontSize = 8.sp,
            color = if (activeHighlight) {
                Color.White.copy(alpha = 0.8f)
            } else {
                MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.6f)
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun SpectrumGrid(
    bands: List<SpectrumBand>,
    peakBandIndex: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val rows = bands.chunked(8)
        
        for ((rowIdx, rowBands) in rows.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (band in rowBands) {
                    val isPeak = band.index == peakBandIndex
                    val isActive = band.value > 0.25f
                    
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val bgColor: Color = when {
                            isPeak -> MiuixTheme.colorScheme.primary
                            isActive -> MiuixTheme.colorScheme.primary.copy(alpha = 0.7f)
                            else -> MiuixTheme.colorScheme.sliderBackground
                        }
                        
                        val fillColor: Color = if (isPeak || isActive) {
                            Color.White.copy(alpha = 0.6f)
                        } else {
                            MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.5f)
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(bgColor),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(band.value.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(fillColor),
                            )
                        }
                        
                        Text(
                            text = "#${band.index}",
                            fontSize = 9.sp,
                            color = if (isPeak) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                        
                        Text(
                            text = "${band.freqStart.toInt()}",
                            fontSize = 7.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            
            if (rowIdx < rows.lastIndex) {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun BandBar(
    label: String,
    value: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = tween(60),
        label = label,
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.width(32.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MiuixTheme.colorScheme.sliderBackground),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun appLabel(packageName: String?): String? {
    if (packageName == null) return null
    val context = LocalContext.current
    return remember(packageName) {
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString() ?: packageName
        } catch (t: Throwable) {
            packageName
        }
    }
}

private val defaultBandsCache: List<SpectrumBand> by lazy {
    val numBands = 32
    val minFreq = 30f
    val maxFreq = 16000f

    List(numBands) { idx ->
        val t = idx.toFloat() / numBands
        val startFreq = (minFreq * (maxFreq.toDouble() / minFreq.toDouble()).pow(t.toDouble())).toFloat()
        val tEnd = (idx + 1).toFloat() / numBands
        val endFreq = (minFreq * (maxFreq.toDouble() / minFreq.toDouble()).pow(tEnd.toDouble())).toFloat()
        SpectrumBand(
            index = idx,
            value = 0f,
            freqStart = startFreq,
            freqEnd = endFreq,
        )
    }
}

@Composable
private fun LevelBar(
    level: Float,
    beat: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(60),
        label = "level",
    )
    val color by animateColorAsState(
        targetValue = if (beat) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.primary.copy(alpha = 0.55f)
        },
        animationSpec = tween(80),
        label = "barColor",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MiuixTheme.colorScheme.sliderBackground),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedLevel.coerceAtLeast(0.02f))
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}