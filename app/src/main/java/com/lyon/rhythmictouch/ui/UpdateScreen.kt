package com.lyon.rhythmictouch.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyon.rhythmictouch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL

private const val GITHUB_API_LATEST =
    "https://api.github.com/repos/LyonHyrik/RhythmicTouch/releases/latest"

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releasePage: String,
    val changelog: String,
)

object UpdateChecker {
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(GITHUB_API_LATEST).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val code = connection.responseCode
            if (code != 200) {
                connection.disconnect()
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v")
            val page = json.optString("html_url", GITHUB_API_LATEST)
            val changelog = json.optString("body", "").trim()
            val assets = json.optJSONArray("assets")
            var downloadUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            if (downloadUrl == null) {
                downloadUrl = page
            }
            UpdateInfo(
                latestVersion = tag,
                downloadUrl = downloadUrl,
                releasePage = page,
                changelog = changelog,
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun isNewer(latest: String): Boolean {
        val current = BuildConfig.VERSION_NAME
        val cParts = current.split('.').mapNotNull { it.toIntOrNull() }
        val lParts = latest.split('.').mapNotNull { it.toIntOrNull() }
        val size = maxOf(cParts.size, lParts.size)
        for (i in 0 until size) {
            val c = cParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}

@Composable
fun UpdateScreen(
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val context = LocalContext.current.applicationContext
    var checking by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var hasUpdate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val result = UpdateChecker.fetchLatest()
        checking = false
        if (result == null) {
            error = true
        } else {
            info = result
            hasUpdate = UpdateChecker.isNewer(result.latestVersion)
        }
    }

    fun download(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "检查更新",
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = "返回",
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { scaleX = -1f },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            when {
                checking -> {
                    Text(
                        text = "正在检查更新...",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 14.sp,
                    )
                }

                error -> {
                    Text(
                        text = "检查失败，请检查网络后重试",
                        color = MiuixTheme.colorScheme.error,
                        fontSize = 14.sp,
                    )
                }

                hasUpdate && info != null -> {
                    Text(
                        text = "发现新版本 v${info!!.latestVersion}",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "当前版本 v${BuildConfig.VERSION_NAME}",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(20.dp))

                    if (info!!.changelog.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    text = "更新日志",
                                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = info!!.changelog,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { download(info!!.downloadUrl) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                    ) {
                        Text("前往浏览器下载", color = androidx.compose.ui.graphics.Color.White)
                    }
                }

                else -> {
                    Text(
                        text = "当前已是最新版本 v${BuildConfig.VERSION_NAME}",
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
