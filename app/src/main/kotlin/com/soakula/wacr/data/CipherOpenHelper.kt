package com.soakula.wacr.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/** Explicit corruption handling: never delegate to a handler which could delete user data. */
internal class CipherOpenHelper(
    configuration: SupportSQLiteOpenHelper.Configuration,
    private val password: ByteArray
) : SupportSQLiteOpenHelper {
    private var closed = false
    private val helper = object : SQLiteOpenHelper(
        configuration.context, configuration.name, password, null, configuration.callback.version,
        0, preserveCorruptDatabase, null, true
    ) {
        override fun onCreate(db: SQLiteDatabase) = configuration.callback.onCreate(db)
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            configuration.callback.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            configuration.callback.onDowngrade(db, oldVersion, newVersion)
        override fun onConfigure(db: SQLiteDatabase) = configuration.callback.onConfigure(db)
        override fun onOpen(db: SQLiteDatabase) = configuration.callback.onOpen(db)
    }

    override val databaseName: String? get() = helper.databaseName

    @Synchronized override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        check(!closed)
        helper.setWriteAheadLoggingEnabled(enabled)
    }

    override val writableDatabase: SupportSQLiteDatabase
        @Synchronized get() {
            check(!closed)
            return helper.writableDatabase
        }

    override val readableDatabase: SupportSQLiteDatabase
        @Synchronized get() {
            check(!closed)
            return helper.readableDatabase
        }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try {
            helper.close()
        } finally {
            // SQLCipher keeps this array for connection creation; wipe only after closing.
            password.fill(0)
        }
    }

    companion object {
        internal val preserveCorruptDatabase = DatabaseErrorHandler { _, error -> throw error }
    }
}
