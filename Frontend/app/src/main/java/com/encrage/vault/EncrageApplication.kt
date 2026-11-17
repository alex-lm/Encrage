package com.encrage.vault

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.encrage.vault.backup.BackupScheduler
import com.encrage.vault.media.MediaStoreObserverRegistrar
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EncrageApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: BackupScheduler
    @Inject lateinit var mediaObserver: MediaStoreObserverRegistrar

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

    override fun onCreate() {
        super.onCreate()
        scheduler.scheduleReconciliation()
        mediaObserver.register()
    }
}
