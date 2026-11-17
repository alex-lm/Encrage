package com.encrage.vault.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.MediaStore
import com.encrage.vault.backup.BackupScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreObserverRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: BackupScheduler,
) {
    private var registered = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = scheduler.requestImmediateScan()
    }

    @Synchronized
    fun register() {
        if (registered) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else MediaStore.Files.getContentUri("external")
        context.contentResolver.registerContentObserver(uri, true, observer)
        registered = true
    }
}
