package com.soakula.wacr.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.soakula.wacr.detection.NameExtractor
import kotlinx.coroutines.flow.Flow

enum class RuleKind { CONTACT, GROUP }
enum class RecordingMode { AUDIO, AUDIO_VIDEO }

@Entity(tableName = "contact_rule", indices = [Index(value = ["kind", "match_key"], unique = true)])
data class ContactRuleEntity(
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "match_key") val matchKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val kind: RuleKind = RuleKind.CONTACT,
    @ColumnInfo(name = "mode_override") val modeOverride: RecordingMode? = null,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

@Dao
interface ContactRuleDao {
    @Insert suspend fun insert(rule: ContactRuleEntity): Long

    @Query("SELECT * FROM contact_rule ORDER BY display_name COLLATE NOCASE, id")
    fun observe(): Flow<List<ContactRuleEntity>>

    @Query("SELECT * FROM contact_rule WHERE kind = :kind AND match_key = :matchKey")
    suspend fun matching(kind: RuleKind, matchKey: String): ContactRuleEntity?

    @Query("SELECT * FROM contact_rule WHERE id = :id")
    suspend fun byId(id: Long): ContactRuleEntity?

    @Query("DELETE FROM contact_rule WHERE id = :id")
    suspend fun delete(id: Long): Int
}

class ContactRuleRepository(private val database: AppDatabase) {
    private val dao = database.contactRules()

    fun observe(): Flow<List<ContactRuleEntity>> = dao.observe()

    suspend fun addContact(displayName: String, createdAt: Long): ContactRuleEntity {
        require(createdAt >= 0)
        val name = NameExtractor.extract(displayName)
        val key = requireNotNull(name.matchKey) { "Contact needs a readable display name" }
        val rule = ContactRuleEntity(name.displayName, key, createdAt)
        return rule.copy(id = dao.insert(rule))
    }

    suspend fun matchingContact(displayName: String): ContactRuleEntity? {
        val key = NameExtractor.matchKey(displayName) ?: return null
        return dao.matching(RuleKind.CONTACT, key)
    }

    suspend fun remove(id: Long): ContactRuleEntity {
        require(id > 0)
        return database.withTransaction {
            val rule = checkNotNull(dao.byId(id)) { "Contact rule does not exist" }
            check(dao.delete(id) == 1)
            rule
        }
    }

    suspend fun restore(rule: ContactRuleEntity) {
        require(rule.id > 0 && rule.createdAt >= 0)
        val name = NameExtractor.extract(rule.displayName)
        require(name.matchKey != null && name.displayName == rule.displayName && name.matchKey == rule.matchKey)
        check(dao.insert(rule) == rule.id)
    }
}
