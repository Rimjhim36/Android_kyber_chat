package com.example.kyberchat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.kyberchat.data.MessageEntity
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    myClientId: String,
    recipientId: String,
    messages: List<MessageEntity>,
    isRecipientOnline: Boolean,
    // Recipient's profile picture (base64) and last-seen timestamp (millis).
    // Both are optional and default to null so existing call sites keep compiling.
    profilePictureBase64: String? = null,
    lastSeenTimestamp: Long? = null,
    // Called once when the screen opens so the caller can trigger a profile fetch
    // (mirrors HomeScreen's onContactProfileNeeded pattern).
    onProfileNeeded: () -> Unit = {},
    onSend: (String) -> Unit,
    onSendImage: (String) -> Unit,
    onDelete: () -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit = {},
    // called with the ids of selected messages when the user picks "Delete for everyone"
    onDeleteForEveryone: (Set<Long>) -> Unit = {},
    onBack: () -> Unit,
    onMessagesVisible: () -> Unit = {}
) {
    var messageText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showSelectedDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            onMessagesVisible()
        }
    }

    // Refresh the recipient's profile (picture / last seen) when the screen opens,
    // and again whenever their online status flips -- this keeps "last seen" accurate
    // right after they go offline instead of showing a stale cached value.
    LaunchedEffect(recipientId, isRecipientOnline) {
        onProfileNeeded()
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    val maxDimension = 1024
                    val scaledBitmap = scaleBitmap(originalBitmap, maxDimension)

                    val outputStream = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                    val imageBytes = outputStream.toByteArray()
                    val base64Image = java.util.Base64.getEncoder().encodeToString(imageBytes)

                    onSendImage(base64Image)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Chat") },
            text = { Text("Are you sure you want to delete this conversation? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) { Text("Delete", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    // This is a custom Dialog (instead of a plain 2-button AlertDialog)
    // so it can offer "Delete for everyone" (own messages only), "Delete for me", and Cancel
    if (showSelectedDeleteDialog) {
        val selectedMessages = messages.filter { selectedIds.contains(it.id) }
        // "Delete for everyone" is only offered when every selected message is ours AND not already deleted
        val canDeleteForEveryone = selectedMessages.isNotEmpty() && selectedMessages.all { it.isFromMe && !it.isDeleted }

        Dialog(onDismissRequest = { showSelectedDeleteDialog = false }) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Delete ${selectedIds.size} message${if (selectedIds.size > 1) "s" else ""}?",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (canDeleteForEveryone) {
                        TextButton(
                            onClick = {
                                onDeleteForEveryone(selectedIds)
                                isSelectionMode = false
                                selectedIds = emptySet()
                                showSelectedDeleteDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Delete for everyone", color = Color.Red)
                        }
                    }

                    TextButton(
                        onClick = {
                            onDeleteSelected(selectedIds)
                            isSelectionMode = false
                            selectedIds = emptySet()
                            showSelectedDeleteDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete for me", color = if (canDeleteForEveryone) MaterialTheme.colorScheme.onSurface else Color.Red)
                    }

                    TextButton(
                        onClick = { showSelectedDeleteDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showSelectedDeleteDialog = true
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        // Title shows the recipient's avatar next to their name/status.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarCircle(
                                label = recipientId,
                                pictureBase64 = profilePictureBase64,
                                size = 40.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(recipientId)
                                Text(
                                    // Shows "Online" when live, otherwise the formatted
                                    // last-seen string (today / yesterday / older day name).
                                    text = if (isRecipientOnline) "Online" else formatLastSeen(lastSeenTimestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRecipientOnline) Color(0xFF2E7D32) else Color.Gray
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(androidx.compose.material.icons.Icons.Default.Delete, contentDescription = "Delete Chat")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(8.dp)
            ) {
                items(messages) { msg ->
                    MessageBubble(
                        message = msg,
                        myClientId = myClientId,
                        isSelected = selectedIds.contains(msg.id),
                        selectionMode = isSelectionMode,
                        onLongPress = {
                            isSelectionMode = true
                            selectedIds = selectedIds + msg.id
                        },
                        onTap = {
                            if (isSelectionMode) {
                                selectedIds = if (selectedIds.contains(msg.id)) {
                                    selectedIds - msg.id
                                } else {
                                    selectedIds + msg.id
                                }
                                if (selectedIds.isEmpty()) isSelectionMode = false
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") }
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach Image")
                }
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message...") },
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            onSend(messageText)
                            messageText = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}

private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= maxDimension && height <= maxDimension) return bitmap

    val ratio = width.toFloat() / height.toFloat()
    val newWidth: Int
    val newHeight: Int
    if (width > height) {
        newWidth = maxDimension
        newHeight = (maxDimension / ratio).toInt()
    } else {
        newHeight = maxDimension
        newWidth = (maxDimension * ratio).toInt()
    }
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageEntity,
    myClientId: String,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {}
) {
    val align = if (message.isFromMe) Alignment.End else Alignment.Start
    val color = if (message.isFromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.isFromMe) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (message.isFromMe) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .combinedClickable(
                onClick = { onTap() },
                onLongClick = { onLongPress() }
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = align
    ) {
        Surface(
            shape = shape,
            color = color,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Deleted-for-everyone messages show a placeholder instead of real content
                if (message.isDeleted) {
                    Text(
                        text = "This message was deleted",
                        color = textColor.copy(alpha = 0.7f),
                        fontStyle = FontStyle.Italic
                    )
                } else if (message.messageType == "IMAGE") {
                    val bitmap = remember(message.content) {
                        try {
                            val bytes = java.util.Base64.getDecoder().decode(message.content)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Sent image",
                            modifier = Modifier
                                .widthIn(max = 250.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Text("Image failed to load", color = textColor)
                    }
                } else {
                    Text(message.content, color = textColor)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    if (message.isFromMe && !message.isDeleted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusTicks(status = message.status)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageStatusTicks(status: String) {
    val greyTick = Color.White.copy(alpha = 0.7f)
    val blueTick = Color(0xFF34B7F1)

    when (status) {
        "SENT" -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Sent",
                tint = greyTick,
                modifier = Modifier.size(14.dp)
            )
        }
        "DELIVERED" -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Delivered",
                tint = greyTick,
                modifier = Modifier.size(14.dp)
            )
        }
        "SEEN" -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Seen",
                tint = blueTick,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// Formats a message's own timestamp (shown inside the chat bubble) as "h:mm a".
fun formatTimestamp(ts: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(ts))
}

// Formats the recipient's last-seen timestamp, in three cases:
// - Today     -> "h:mm a"                    (e.g. "11:07 AM")
// - Yesterday -> "yesterday at h:mm a"
// - Older     -> "last seen EEEE at h:mm a"   (e.g. "last seen Monday at 11:07 AM")
// Returns "Offline" if no timestamp is available yet.
fun formatLastSeen(timestamp: Long?): String {
    if (timestamp == null) return "Offline"

    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val timeStr = timeFormat.format(Date(timestamp))

    val lastSeenCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val todayCal = Calendar.getInstance()
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    return when {
        isSameDay(lastSeenCal, todayCal) -> timeStr
        isSameDay(lastSeenCal, yesterdayCal) -> "yesterday at $timeStr"
        else -> {
            val dayName = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(timestamp))
            "last seen $dayName at $timeStr"
        }
    }
}

// Helper: checks if two Calendar instances fall on the same calendar day.
private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}