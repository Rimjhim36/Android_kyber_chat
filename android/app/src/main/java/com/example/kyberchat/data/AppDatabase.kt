package com.example.kyberchat.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

data class UnreadCount(
    val contactId: String,
    val count: Int
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE (senderId = :contactId AND recipientId = :myId) OR (senderId = :myId AND recipientId = :contactId) ORDER BY timestamp DESC")
    fun getMessagesForContact(myId: String, contactId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT DISTINCT senderId FROM messages WHERE recipientId = :myId UNION SELECT DISTINCT recipientId FROM messages WHERE senderId = :myId")
    fun getRecentContacts(myId: String): Flow<List<String>>

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE (senderId = :contactId AND recipientId = :myId) OR (senderId = :myId AND recipientId = :contactId)")
    suspend fun deleteChat(myId: String, contactId: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<Long>)

    @Query("UPDATE messages SET status = :status WHERE serverMsgId = :serverMsgId")
    suspend fun updateStatus(serverMsgId: String, status: String)

    @Query("SELECT * FROM messages WHERE senderId = :contactId AND recipientId = :myId AND isRead = 0 AND serverMsgId != ''")
    suspend fun getUnseenIncoming(myId: String, contactId: String): List<MessageEntity>

    @Query("UPDATE messages SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("""
        SELECT senderId AS contactId, COUNT(*) AS count 
        FROM messages 
        WHERE recipientId = :myId AND isFromMe = 0 AND isRead = 0 
        GROUP BY senderId
    """)
    fun getUnreadCounts(myId: String): Flow<List<UnreadCount>>

    // NEW: fetch the selected rows by local id, needed to read each one's
    // serverMsgId/recipientId before sending the delete-for-everyone WS frame.
    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getMessagesByIds(ids: List<Long>): List<MessageEntity>

    // NEW: marks the given local rows as deleted-for-everyone (used on the SENDER's device).
    @Query("UPDATE messages SET isDeleted = 1 WHERE id IN (:ids)")
    suspend fun markDeletedByIds(ids: List<Long>)

    // NEW: marks a message as deleted-for-everyone by its server msg_id
    // (used on the RECIPIENT's device when a delete event arrives over WebSocket).
    @Query("UPDATE messages SET isDeleted = 1 WHERE serverMsgId = :serverMsgId")
    suspend fun markDeletedByServerId(serverMsgId: String)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN messageType TEXT NOT NULL DEFAULT 'TEXT'"
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN serverMsgId TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN status TEXT NOT NULL DEFAULT 'SENT'"
        )
    }
}

// Migration 3 -> 4, added isDeleted column for the delete-for-everyone feature.
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0"
        )
    }
}

@Database(entities = [MessageEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kyber_chat_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}