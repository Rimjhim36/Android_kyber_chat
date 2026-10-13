package com.example.kyberchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kyberchat.network.ContactInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(
    users: List<ContactInfo>,
    onUserClick: (String) -> Unit,
    onBack: () -> Unit 
) {
    // NEW: state to hold the search query
    var searchQuery by remember { mutableStateOf("") }

    // filtered list based on the search query
    val filteredUsers = remember(users, searchQuery) {
        if (searchQuery.isBlank()) {
            users
        } else {        
            users.filter { it.clientId.contains(searchQuery, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registered Users") },
                navigationIcon = { 
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search users...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            if (filteredUsers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (searchQuery.isBlank()) "No registered users found." else "No user matches \"$searchQuery\"",
                        color = Color.Gray
                    )
                }
            } else {

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(filteredUsers) { user ->
                        UserRow(user) { onUserClick(user.clientId) }
                    }
                }
            }
        }
    }
}

@Composable
fun UserRow(user: ContactInfo, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // CHANGED: was a plain Surface+Text letter circle, now uses the
            // shared AvatarCircle (from HomeScreen.kt) so it shows the real
            // photo when available, same as the Home screen's chat list.
            AvatarCircle(label = user.clientId, pictureBase64 = user.picture)

            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = user.clientId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = if (user.online) "Online" else "Offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (user.online) Color(0xFF2E7D32) else Color.Gray
                )
            }
        }
    }
}