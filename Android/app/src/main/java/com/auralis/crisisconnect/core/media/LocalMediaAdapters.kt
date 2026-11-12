package com.auralis.crisisconnect.core.media

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import coil.ImageLoader
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer
import java.io.File

internal fun localMediaImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .components { add(LocalMediaCachePolicy()); add(LocalMediaFetcher.FileFactory()); add(LocalMediaFetcher.UriFactory()) }
    .build()

private class LocalMediaCachePolicy : coil.intercept.Interceptor {
    override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
        val file = when (val data = chain.request.data) {
            is File -> data
            is Uri -> LocalMedia.file(data)
            is String -> LocalMedia.file(data.toUri())
            else -> null
        }
        val request = if (file != null && LocalMedia.manages(file)) {
            chain.request.newBuilder().diskCachePolicy(coil.request.CachePolicy.DISABLED).build()
        } else chain.request
        return chain.proceed(request)
    }
}

internal class LocalMediaFetcher(private val file: File, private val options: Options) : Fetcher {
    override suspend fun fetch() = SourceResult(
        source = ImageSource(Buffer().write(LocalMedia.read(file)), options.context),
        mimeType = null, dataSource = coil.decode.DataSource.MEMORY
    )
    class FileFactory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (LocalMedia.manages(data)) LocalMediaFetcher(data, options) else null
    }
    class UriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            LocalMedia.file(data)?.takeIf(LocalMedia::manages)?.let { LocalMediaFetcher(it, options) }
    }
}

@UnstableApi
internal class LocalMediaDataSourceFactory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource = object : DataSource {
        private var delegate: DataSource? = null
        private val listeners = mutableListOf<TransferListener>()
        override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener; delegate?.addTransferListener(transferListener) }
        override fun open(dataSpec: DataSpec): Long {
            val file = LocalMedia.file(dataSpec.uri)
            val source: DataSource = if (file != null && LocalMedia.manages(file)) ByteArrayDataSource(LocalMedia.read(file))
                else DefaultDataSource.Factory(context).createDataSource()
            delegate = source
            listeners.forEach(source::addTransferListener)
            return source.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(delegate).read(buffer, offset, length)
        override fun getUri(): Uri? = delegate?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()
        override fun close() { delegate?.close(); delegate = null }
    }
}
