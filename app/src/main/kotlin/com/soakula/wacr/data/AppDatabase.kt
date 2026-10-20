package com.soakula.wacr.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.google.crypto.tink.Aead
import com.soakula.wacr.crypto.DatabaseSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@Database(
    entities = [CallSessionEntity::class, RecordingEntity::class, SegmentEntity::class, RouteEventEntity::class],
    version = 1, exportSchema = true
)
@TypeConverters(StorageConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao

    companion object {
        const val NAME = "wacr.db"

        suspend fun open(context: Context, encryption: Aead): AppDatabase = withContext(Dispatchers.IO) {
            val application = context.applicationContext
            val password = DatabaseSecret.open(application, application.getDatabasePath(NAME), encryption).load()
            var database: AppDatabase? = null
            var opened = false
            try {
                System.loadLibrary("sqlcipher")
                database = Room.databaseBuilder(application, AppDatabase::class.java, NAME)
                    .openHelperFactory(SupportSQLiteOpenHelper.Factory { CipherOpenHelper(it, password) })
                    .build()
                database.openHelper.writableDatabase
                opened = true
                database
            } finally {
                if (!opened) {
                    try {
                        database?.close()
                    } finally {
                        password.fill(0)
                    }
                }
            }
        }
    }
}

class StorageConverters {
    @TypeConverter fun uuidToString(value: UUID): String = value.toString()
    @TypeConverter fun stringToUuid(value: String): UUID = UUID.fromString(value)
}
