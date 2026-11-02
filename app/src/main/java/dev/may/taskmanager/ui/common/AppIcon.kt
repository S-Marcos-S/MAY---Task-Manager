// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.may.taskmanager.data.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object IconMemoryCache {
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return ((value.width.toLong() * value.height.toLong() * 4L) / 1024L).toInt().coerceAtLeast(1)
        }
    }

    fun get(pkg: String): ImageBitmap? = cache.get(pkg)
    fun put(pkg: String, bitmap: ImageBitmap) = cache.put(pkg, bitmap)
}

@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    sizeDp: Int? = null,
) {
    val actualSize = sizeDp?.dp ?: size
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf(IconMemoryCache.get(packageName)) }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val drawable = pm.getApplicationIcon(packageName)
                    val px = (actualSize.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)
                    drawableToBitmap(drawable, px).asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            }
            if (loaded != null) {
                IconMemoryCache.put(packageName, loaded)
                bitmap = loaded
            }
        }
    }

    Box(
        modifier = modifier
            .size(actualSize)
            .clip(RoundedCornerShape(actualSize * 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(actualSize),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(actualSize)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Android,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(actualSize * 0.65f),
                )
            }
        }
    }
}

@Composable
fun AppIcon(
    app: AppInfo,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    sizeDp: Int? = null,
) {
    AppIcon(
        packageName = app.packageName,
        modifier = modifier,
        size = size,
        sizeDp = sizeDp,
    )
}

private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bmp
}
