package com.mobileailocal.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val systemPrompt: String = "",
    val modelId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "message",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"], childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String, // user / assistant / system
    val content: String,
    @ColumnInfo(defaultValue = "0") val tokenCount: Int = 0,
    val createdAt: Long,
    /** どちらが生成したか: "local" / "cloud"。 */
    @ColumnInfo(defaultValue = "local") val origin: String = "local",
)

/** 連携メタデータのローカル写し(キーは含まない)。アカウント(uid)ごとに行を持つ。 */
@Entity(tableName = "cloud_integration", primaryKeys = ["uid", "id"])
data class CloudIntegrationEntity(
    val uid: String,
    val id: String,
    val type: String, // ProviderType.name
    val displayName: String,
    val baseUrl: String,
    val enabledModelsJson: String,
    val lastModel: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val syncState: String, // local / synced / deleted
)

@Entity(tableName = "model")
data class ModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val quant: String,
    val contextLength: Int,
    val source: String, // download / import
    val license: String,
    val installedAt: Long,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversation ORDER BY updatedAt DESC")
    fun conversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM message WHERE conversationId = :id ORDER BY id")
    fun messages(id: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun conversation(id: Long): ConversationEntity?

    @Query("UPDATE conversation SET systemPrompt = :prompt WHERE id = :id")
    fun updateSystemPrompt(id: Long, prompt: String)

    @Insert fun insertConversation(c: ConversationEntity): Long
    @Insert fun insertMessage(m: MessageEntity): Long

    @Query("UPDATE message SET content = :content WHERE id = :id")
    fun updateMessageContent(id: Long, content: String)

    @Query("UPDATE conversation SET title = :title, updatedAt = :now WHERE id = :id")
    fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE conversation SET modelId = :modelId WHERE id = :id")
    fun updateModelId(id: Long, modelId: String?)

    @Query("UPDATE conversation SET updatedAt = :now WHERE id = :id")
    fun touch(id: Long, now: Long)

    @Query("SELECT id FROM message WHERE conversationId = :cid AND role = 'user' ORDER BY id DESC LIMIT 1")
    fun lastUserMessageId(cid: Long): Long?

    @Query("DELETE FROM message WHERE conversationId = :cid AND id > :id")
    fun deleteMessagesAfter(cid: Long, id: Long)

    @Query("DELETE FROM conversation WHERE id = :id")
    fun deleteConversation(id: Long)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM model ORDER BY installedAt DESC")
    fun models(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM model WHERE id = :id")
    fun byId(id: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(m: ModelEntity)

    @Query("DELETE FROM model WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface CloudIntegrationDao {
    @Query("SELECT * FROM cloud_integration WHERE uid = :uid AND syncState != 'deleted' ORDER BY createdAt")
    fun integrations(uid: String): Flow<List<CloudIntegrationEntity>>

    @Query("SELECT * FROM cloud_integration WHERE uid = :uid AND syncState != 'deleted' ORDER BY createdAt")
    fun list(uid: String): List<CloudIntegrationEntity>

    @Query("SELECT * FROM cloud_integration WHERE uid = :uid AND id = :id")
    fun byId(uid: String, id: String): CloudIntegrationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(e: CloudIntegrationEntity)

    @Query("DELETE FROM cloud_integration WHERE uid = :uid AND id = :id")
    fun delete(uid: String, id: String)

    @Query("DELETE FROM cloud_integration WHERE uid = :uid")
    fun deleteAll(uid: String)
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, ModelEntity::class, CloudIntegrationEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun modelDao(): ModelDao
    abstract fun cloudIntegrationDao(): CloudIntegrationDao
}
