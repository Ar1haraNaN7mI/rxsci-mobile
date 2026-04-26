package com.x.rxsciapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val title: String,
    val sourceChannel: String,
    val chatId: String,
    val lastMessage: String,
    val updatedAt: String,
    val writable: Boolean,
    val archived: Boolean = false,
)

@Entity(
    tableName = "messages",
    indices = [
        Index("sessionId"),
        Index("clientMessageId"),
    ],
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: String,
    val sourceChannel: String,
    val replyTo: String?,
    val clientMessageId: String?,
    val deliveryState: String,
    val statusType: String?,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val attachmentId: String,
    val messageId: String,
    val name: String,
    val kind: String,
    val size: Long,
    val downloadPath: String?,
    val localUri: String?,
)

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(
        parentColumn = "messageId",
        entityColumn = "messageId",
    )
    val attachments: List<AttachmentEntity>,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    fun observeSession(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findSession(sessionId: String): SessionEntity?

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Upsert
    suspend fun upsertAll(items: List<SessionEntity>)

    @Query("UPDATE sessions SET archived = :archived WHERE sessionId = :sessionId")
    suspend fun setArchived(sessionId: String, archived: Boolean)

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

@Dao
interface MessageDao {
    @Transaction
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, messageId ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageWithAttachments>>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Upsert
    suspend fun upsertAttachments(items: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND clientMessageId = :clientMessageId LIMIT 1")
    suspend fun findByClientMessageId(
        sessionId: String,
        clientMessageId: String,
    ): MessageEntity?

    @Query("UPDATE attachments SET localUri = :localUri WHERE attachmentId = :attachmentId")
    suspend fun updateAttachmentLocalUri(attachmentId: String, localUri: String)
}

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MobileDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        fun create(context: Context): MobileDatabase {
            return Room.databaseBuilder(
                context,
                MobileDatabase::class.java,
                "rxsci-mobile.db",
            ).fallbackToDestructiveMigration(dropAllTables = true).build()
        }
    }
}
