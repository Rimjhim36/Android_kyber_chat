package com.example.kyberchat.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.kyberchat.network.ProfileResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    myClientId: String,
    contacts: List<String>,
    isConnected: Boolean,
    unreadCounts: Map<String, Int> = emptyMap(),
    contactProfiles: Map<String, ProfileResponse> = emptyMap(), // NEW: contactId -> profile (name/picture) cache
    onContactProfileNeeded: (String) -> Unit = {},               // NEW: called once per visible contact to trigger a fetch
    onChatClick: (String) -> Unit,
    onNewChatClick: () -> Unit,
    onEditProfile: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kyber Chat")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = if (isConnected) Color.Green else Color.Red,
                                modifier = Modifier.size(8.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isConnected) "Connected" else "Disconnected",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                },
                actions = {
                    // OLD: plain "Logout" text button (kept here as a comment for reference).
                    // TextButton(onClick = onLogout) {
                    //     Text("Logout", color = Color.White)
                    // }

                    // Three-dot menu with "Edit Profile" and "Logout" options
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit Profile") },
                                onClick = {
                                    menuExpanded = false
                                    onEditProfile()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Logout") },
                                onClick = {
                                    menuExpanded = false
                                    onLogout()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChatClick) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (contacts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No chats yet. Tap + to start one!", color = Color.Gray)
                }
            } else {
                LazyColumn {
                    items(contacts) { contactId ->
                        // NEW: trigger a profile fetch for this contact the first time
                        // its row appears. fetchProfile() in the ViewModel is a no-op
                        // if already cached, so this is safe to call every recomposition.
                        LaunchedEffect(contactId) {
                            onContactProfileNeeded(contactId)
                        }
                        val unreadCount = unreadCounts[contactId] ?: 0
                        val profile = contactProfiles[contactId]
                        ContactItem(contactId, unreadCount, profile?.picture) { onChatClick(contactId) }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactItem(
    contactId: String,
    unreadCount: Int,
    pictureBase64: String? = null, // NEW
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            
            AvatarCircle(label = contactId, pictureBase64 = pictureBase64)

            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contactId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                )
                Text(text = "Tap to chat", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }

            if (unreadCount > 0) {
                UnreadBadge(count = unreadCount)
            }
        }
    }
}

@Composable
fun AvatarCircle(
    label: String,
    pictureBase64: String?,
    size: Dp = 50.dp
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(size)
    ) {
        val bitmap = remember(pictureBase64) {
            if (pictureBase64.isNullOrEmpty()) {
                null
            } else {
                try {
                    val bytes = Base64.decode(pictureBase64, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Profile Picture",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
fun UnreadBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = Color(0xFF25D366),
        modifier = Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}