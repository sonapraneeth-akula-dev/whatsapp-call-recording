package com.soakula.wacr.data

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class CipherOpenHelperTest {
    @Test fun closeWipesRetainedPassphraseAndCorruptionThrowsWithoutDeleting() {
        val application = RuntimeEnvironment.getApplication()
        val file = application.getDatabasePath("preserve.db")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        file.writeBytes(byteArrayOf(1, 2, 3))
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(application)
            .name(file.name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val password = ByteArray(32) { 99 }
        val helper = CipherOpenHelper(configuration, password)
        val corruption = SQLiteException("synthetic corruption")
        assertSame(corruption, assertThrows(SQLiteException::class.java) {
            CipherOpenHelper.preserveCorruptDatabase.onCorruption(null, corruption)
        })
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        helper.close()
        helper.close()
        assertTrue(password.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { helper.writableDatabase }
    }
}
