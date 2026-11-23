package com.encrage.vault.media

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * Gallery-tile model for a phone item. On Android 10+ it is served from the platform thumbnail cache
 * (`ContentResolver.loadThumbnail`), which is much cheaper than decoding a 12–50 MP original or pulling a
 * video frame for every tile while flinging.
 */
data class LocalThumbnail(val uri: String)

/** What a gallery tile should hand to Coil for a phone item. */
fun localTileModel(mediaStoreUri: String): Any =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) LocalThumbnail(mediaStoreUri) else Uri.parse(mediaStoreUri)

@RequiresApi(Build.VERSION_CODES.Q)
class LocalThumbnailFetcher(
    private val data: LocalThumbnail,
    private val options: Options,
    private val resolver: ContentResolver,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { DEFAULT_EDGE }.coerceIn(MIN_EDGE, MAX_EDGE)
        val height = options.size.height.pxOrElse { DEFAULT_EDGE }.coerceIn(MIN_EDGE, MAX_EDGE)
        val signal = CancellationSignal()
        // Abandon the platform decode as soon as the tile scrolls away.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) signal.cancel() }
        val bitmap = try {
            resolver.loadThumbnail(Uri.parse(data.uri), android.util.Size(width, height), signal)
        } finally {
            handle?.dispose()
        }
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<LocalThumbnail> {
        override fun create(data: LocalThumbnail, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) LocalThumbnailFetcher(data, options, resolver) else null
    }

    private companion object {
        const val DEFAULT_EDGE = 384
        const val MIN_EDGE = 96
        const val MAX_EDGE = 1024
    }
}

class LocalThumbnailKeyer : Keyer<LocalThumbnail> {
    override fun key(data: LocalThumbnail, options: Options): String = "thumb:${data.uri}:${options.size}"
}
