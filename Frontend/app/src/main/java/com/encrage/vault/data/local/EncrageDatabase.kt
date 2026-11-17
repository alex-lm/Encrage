package com.encrage.vault.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class DatabaseConverters {
    @TypeConverter fun mediaType(value: String) = MediaType.valueOf(value)
    @TypeConverter fun mediaType(value: MediaType) = value.name
    @TypeConverter fun localState(value: String) = LocalState.valueOf(value)
    @TypeConverter fun localState(value: LocalState) = value.name
    @TypeConverter fun cloudState(value: String) = CloudState.valueOf(value)
    @TypeConverter fun cloudState(value: CloudState) = value.name
    @TypeConverter fun backupStatus(value: String) = BackupStatus.valueOf(value)
    @TypeConverter fun backupStatus(value: BackupStatus) = value.name
    @TypeConverter fun thumbnailState(value: String) = ThumbnailState.valueOf(value)
    @TypeConverter fun thumbnailState(value: ThumbnailState) = value.name
}

@Database(
    entities = [MediaEntity::class, UploadSessionEntity::class, RemoteCursorEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class EncrageDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun transferDao(): TransferDao
}

