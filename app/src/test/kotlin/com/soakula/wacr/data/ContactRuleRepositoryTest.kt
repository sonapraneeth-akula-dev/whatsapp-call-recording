package com.soakula.wacr.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class ContactRuleRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ContactRuleRepository

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        repository = ContactRuleRepository(database)
    }

    @After fun closeDatabase() { database.close() }

    @Test fun normalizesNamesAndRejectsCollisionsWithoutReplacingTheOriginal() = runBlocking {
        val original = repository.addContact("  Demo   Contact \uD83D\uDE00 ", 1000)
        assertEquals("Demo Contact \uD83D\uDE00", original.displayName)
        assertEquals("demo contact", original.matchKey)
        assertEquals(original, repository.matchingContact("DEMO CONTACT"))
        assertEquals(original, repository.matchingContact("\uFF24\uFF45\uFF4D\uFF4F Contact"))
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { repository.addContact("demo contact", 2000) }
        }
        assertEquals(listOf(original), repository.observe().first())
    }

    @Test fun rejectsUnreadableNamesAndBadTimestamps() = runBlocking {
        for (name in listOf("", "  \n ", "\uD83D\uDE00", "\u200B")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.addContact(name, 0) }
            }
            assertNull(repository.matchingContact(name))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.addContact("Demo", -1) }
        }
        assertTrue(repository.observe().first().isEmpty())
    }

    @Test fun removeAndUndoPreserveIdentityAndFailInsteadOfOverwritingNewRules() = runBlocking<Unit> {
        val original = repository.addContact("Demo", 1000)
        assertEquals(original, repository.remove(original.id))
        assertTrue(repository.observe().first().isEmpty())
        repository.restore(original)
        assertEquals(original, repository.observe().first().single())
        repository.remove(original.id)
        val replacement = repository.addContact("DEMO", 2000)
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { repository.restore(original) } }
        assertEquals(listOf(replacement), repository.observe().first())
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.remove(original.id) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.remove(0) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.restore(original.copy(matchKey = "other")) }
        }
    }

    @Test fun contactAndGroupKeysStaySeparate() = runBlocking {
        val contact = repository.addContact("Demo", 1000)
        val groupId = database.contactRules().insert(contact.copy(id = 0, kind = RuleKind.GROUP))
        assertEquals(contact, repository.matchingContact("Demo"))
        assertEquals(groupId, database.contactRules().matching(RuleKind.GROUP, "demo")?.id)
        assertEquals(2, repository.observe().first().size)
    }
}
