package com.soakula.wacr.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.util.UUID

@Database(
    entities = [CallSessionEntity::class, RecordingEntity::class, SegmentEntity::class, RouteEventEntity::class],
    version = 1, exportSchema = true
)
@TypeConverters(StorageConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
}

class StorageConverters {
    @TypeConverter fun uuidToString(value: UUID): String = value.toString()
    @TypeConverter fun stringToUuid(value: String): UUID = UUID.fromString(value)
}
