package com.example.kyberchat.data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val isRead: Boolean = false,
    val messageType: String = "TEXT",
    val serverMsgId: String = "",
    val status: String = "SENT",
    // NEW: true once this message has been deleted for everyone.
    // Content is kept as-is in the DB, the UI just hides it and shows a placeholder instead.
    val isDeleted: Boolean = false
)