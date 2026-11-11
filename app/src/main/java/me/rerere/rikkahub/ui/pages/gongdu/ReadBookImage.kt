package me.rerere.rikkahub.ui.pages.gongdu

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.reading.ReadImage

/** Local embedded image only. Bounds are checked before allocating pixels; no remote image request. */
@Composable
internal fun ReadBookImage(image: ReadImage?, modifier: Modifier = Modifier, cover: Boolean = false) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val target = with(density) { maxWidth.roundToPx() }.coerceIn(96, 1600)
        val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, image, target) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    val data = image?.data ?: return@withContext null
                    require(data.length <= 6_000_000)
                    val bytes = Base64.decode(data, Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..20_000 && bounds.outHeight in 1..20_000 &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 80_000_000)
                    var sample = 1
                    while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > 2400 ||
                        (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 4_000_000) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
        val loaded = bitmap
        if (loaded != null) Image(loaded, image?.alt?.takeIf { it.isNotBlank() } ?: if (cover) "书籍封面" else "书中插图",
            Modifier.fillMaxSize().testTag(if (cover) "read-cover-image" else "read-inline-image"),
            contentScale = ContentScale.Fit)
        else Text(image?.alt?.takeIf { it.isNotBlank() } ?: if (cover) "书" else "插图暂时无法显示",
            Modifier.padding(12.dp), fontFamily = GongduBook,
            style = if (cover) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
