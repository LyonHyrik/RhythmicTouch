package com.lyon.rhythmictouch.ui

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.res.stringResource
import com.lyon.rhythmictouch.BuildConfig
import com.lyon.rhythmictouch.R
import java.net.HttpURLConnection
import java.net.URL

private const val AUTHOR_NAME = "LyonHyrik"
private const val AUTHOR_BLOG = "https://lyonhyrik.github.io/"
private const val AUTHOR_COOLAPK = "https://www.coolapk.com/u/24533526"
private const val AUTHOR_QQ = "3464313824"
private const val GITHUB_REPO = "https://github.com/LyonHyrik/RhythmicTouch"
private const val GITHUB_API_CONTRIBUTORS =
    "https://api.github.com/repos/LyonHyrik/RhythmicTouch/contributors"

data class Contributor(
    val login: String,
    val avatarUrl: String,
    val profileUrl: String,
    val contributions: Int,
)

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    monetEnabled: Boolean = false,
) {
    BackHandler { onBack() }
    val context = LocalContext.current.applicationContext
    var contributors by remember { mutableStateOf<List<Contributor>?>(null) }
    var showUpdate by remember { mutableStateOf(false) }

    if (showUpdate) {
        UpdateScreen(
            onBack = { showUpdate = false },
            onDismiss = { showUpdate = false },
        )
        return
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            Toast.makeText(context, context.getString(R.string.toast_open_link_error), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        contributors = withContext(Dispatchers.IO) { fetchContributors() }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.screen_about),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = stringResource(R.string.action_back),
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
            Spacer(Modifier.height(28.dp))

            AppIcon(monetEnabled)

            Spacer(Modifier.height(12.dp))

            Text(
                text = "RhythmicTouch",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = stringResource(R.string.app_subtitle),
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                fontSize = 13.sp,
            )

            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.app_description),
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 28.dp),
            )

            Spacer(Modifier.height(20.dp))

            SmallTitle(stringResource(R.string.section_author))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NetworkAvatar(url = "https://q1.qlogo.cn/g?b=qq&nk=$AUTHOR_QQ&s=640", size = 56)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = AUTHOR_NAME,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        val ownContributions = contributors?.firstOrNull { it.login == AUTHOR_NAME }?.contributions
                        Text(
                            text = if (ownContributions != null) "${stringResource(R.string.label_developer_only)} · ${stringResource(R.string.label_contributions, ownContributions)}" else stringResource(R.string.label_developer_only),
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            val otherContributors = contributors?.filter { it.login != AUTHOR_NAME }
            if (!otherContributors.isNullOrEmpty()) {
                SmallTitle(stringResource(R.string.section_contributors))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column {
                        otherContributors.forEach { contributor ->
                            ContributorRow(
                                contributor = contributor,
                                onClick = { openUrl(contributor.profileUrl) },
                            )
                        }
                    }
                }
            }

            SmallTitle(stringResource(R.string.section_contact))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column {
                    AboutRow(title = stringResource(R.string.label_blog), value = AUTHOR_BLOG, onClick = { openUrl(AUTHOR_BLOG) })
                    AboutRow(title = stringResource(R.string.label_coolapk), value = "LyonHyrik", onClick = { openUrl(AUTHOR_COOLAPK) })
                    AboutRow(title = stringResource(R.string.label_github), value = GITHUB_REPO, onClick = { openUrl(GITHUB_REPO) })
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                AboutRow(title = stringResource(R.string.label_check_update), value = stringResource(R.string.label_check_update_desc), onClick = { showUpdate = true })
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Made with \u2665 by LyonHyrik",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AboutRow(
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            color = if (onClick != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainerVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.65f),
        )
        if (onClick != null) {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
}

private fun fetchContributors(): List<Contributor>? {
    return try {
        val connection = URL(GITHUB_API_CONTRIBUTORS).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        if (connection.responseCode != 200) {
            connection.disconnect()
            return null
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val array = JSONArray(body)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    Contributor(
                        login = item.optString("login", ""),
                        avatarUrl = item.optString("avatar_url", ""),
                        profileUrl = item.optString("html_url", ""),
                        contributions = item.optInt("contributions", 0),
                    ),
                )
            }
        }
    } catch (t: Throwable) {
        null
    }
}

@Composable
private fun ContributorRow(
    contributor: Contributor,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkAvatar(url = contributor.avatarUrl, size = 40)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = contributor.login,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.label_contributions, contributor.contributions),
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun AppIcon(monetEnabled: Boolean) {
    val context = LocalContext.current.applicationContext
    val icon = remember(monetEnabled) {
        try {
            if (monetEnabled) {
                val drawable = context.getDrawable(com.lyon.rhythmictouch.R.drawable.ic_launcher_foreground)
                if (drawable != null) {
                    val bitmap = android.graphics.Bitmap.createBitmap(108, 108, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, 108, 108)
                    drawable.draw(canvas)
                    bitmap.asImageBitmap()
                } else {
                    null
                }
            } else {
                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                val bitmap = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth.takeIf { it > 0 } ?: 96,
                    drawable.intrinsicHeight.takeIf { it > 0 } ?: 96,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            }
        } catch (t: Throwable) {
            null
        }
    }

    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            if (monetEnabled) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = icon,
                        contentDescription = stringResource(R.string.content_app_icon),
                        modifier = Modifier.size(64.dp),
                    )
                }
            } else {
                Image(
                    bitmap = icon,
                    contentDescription = stringResource(R.string.content_app_icon),
                    modifier = Modifier.size(64.dp),
                )
            }
        } else {
            Text(
                text = "RT",
                color = if (monetEnabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SmallTitle(text: String) {
    Box(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun NetworkAvatar(url: String, size: Int) {
    var bitmap by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val connection = java.net.URL(url).openConnection()
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val stream = connection.getInputStream()
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()
                bmp?.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
    }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = stringResource(R.string.content_avatar),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        } else {
            Text(
                text = "R",
                color = MiuixTheme.colorScheme.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
