package com.example.kyberchat

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.kyberchat.ui.ChatScreen
import com.example.kyberchat.ui.HomeScreen
import com.example.kyberchat.ui.NewChatScreen
import com.example.kyberchat.ui.ProfileScreen // NEW: profile edit screen
import com.example.kyberchat.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // NEW: keeps the system navigation bar space always reserved/visible,
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.navigationBarColor = AndroidColor.BLACK

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(viewModel)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: ChatViewModel) {
    val navController = rememberNavController()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()

    val startDest = if (isLoggedIn) "home" else "login"

    NavHost(navController = navController, startDestination = startDest) {

        composable("login") {
            val authError by viewModel.authError.collectAsState()
            val loggedIn by viewModel.isLoggedIn.collectAsState()

            LaunchedEffect(loggedIn) {
                if (loggedIn) {
                    navController.navigate("home") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            }

            RegistrationScreen(
                errorMessage = authError,
                onRegister = { id, password ->
                    viewModel.register(id, password)
                }
            )
        }

        composable("home") {
            val contacts by viewModel.contacts.collectAsState()
            val myId by viewModel.clientId.collectAsState()
            val isConnected by viewModel.isConnected.collectAsState()
            val unreadCounts by viewModel.unreadCounts.collectAsState()
            val contactProfiles by viewModel.profileCache.collectAsState()

            HomeScreen(
                myClientId = myId,
                contacts = contacts,
                isConnected = isConnected,
                unreadCounts = unreadCounts,
                contactProfiles = contactProfiles,
                onContactProfileNeeded = { id ->
                    viewModel.fetchProfile(id)
                },
                onChatClick = { contactId ->
                    navController.navigate("chat/$contactId")
                },
                onNewChatClick = {
                    navController.navigate("new_chat")
                },
                onEditProfile = {
                    navController.navigate("profile")
                },
                onLogout = {
                    viewModel.logout()
                    navController.navigate("login") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            )
        }


        composable("new_chat") {
            val allUsers by viewModel.allUsersWithStatus.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.refreshAllUsers()
            }

            NewChatScreen(
                users = allUsers,
                onUserClick = { userId ->
                    navController.navigate("chat/$userId") {
                        popUpTo("home")
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("profile") {
            val profileName by viewModel.profileName.collectAsState()
            val profilePicture by viewModel.profilePicture.collectAsState()
            // NEW: result of the most recent change-password attempt, read by
            // ProfileScreen to show a success/error message.
            val passwordChangeStatus by viewModel.passwordChangeStatus.collectAsState()

            ProfileScreen(
                currentName = profileName,
                currentPictureBase64 = profilePicture,
                onSave = { name, picture ->
                    viewModel.updateProfile(name, picture)
                },
                onBack = { navController.popBackStack() },
                // NEW: wires the Change Password section to the ViewModel.
                onChangePassword = { current, new ->
                    viewModel.changePassword(current, new)
                },
                passwordChangeStatus = passwordChangeStatus,
                onClearPasswordStatus = { viewModel.clearPasswordChangeStatus() }
            )
        }

        composable("chat/{contactId}") { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
            val myId by viewModel.clientId.collectAsState()
            val messages by viewModel.getMessages(contactId).collectAsState(initial = emptyList())
            val onlineStatusMap by viewModel.onlineStatus.collectAsState()
            // NEW: cached profile info (picture + last seen) for the contact, same
            // cache HomeScreen already uses via viewModel.profileCache.
            val contactProfiles by viewModel.profileCache.collectAsState()
            val contactProfile = contactProfiles[contactId]

            ChatScreen(
                myClientId = myId,
                recipientId = contactId,
                messages = messages,
                isRecipientOnline = (contactId == myId) || onlineStatusMap[contactId] == true,
                // NEW: pass the recipient's cached profile picture and last-seen timestamp
                profilePictureBase64 = contactProfile?.picture,
                lastSeenTimestamp = contactProfile?.lastSeen,
                // NEW: triggers a fresh profile fetch (forceRefresh = true) whenever the
                // chat screen opens or the recipient's online status flips, so "last seen"
                // never shows a stale cached value.
                onProfileNeeded = { viewModel.fetchProfile(contactId, forceRefresh = true) },
                onSend = { content -> viewModel.sendMessage(contactId, content) },
                onSendImage = { base64Image -> viewModel.sendImage(contactId, base64Image) },
                onDelete = {
                    viewModel.deleteChat(contactId)
                    navController.popBackStack()
                },
                onDeleteSelected = { ids ->
                    viewModel.deleteSelectedMessages(ids)
                },
                onDeleteForEveryone = { ids ->
                    viewModel.deleteForEveryone(ids)
                },
                onBack = { navController.popBackStack() },
                onMessagesVisible = { viewModel.markMessagesSeen(contactId) }
            )
        }
    }
}

@Composable
fun RegistrationScreen(
    errorMessage: String?,
    onRegister: (String, String) -> Unit
) {
    var clientId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
         modifier = Modifier.fillMaxSize().padding(16.dp),
         verticalArrangement = Arrangement.Center,
         horizontalAlignment = Alignment.CenterHorizontally
    ) {
         Text("Kyber Chat Login", style = MaterialTheme.typography.headlineMedium)
         Spacer(modifier = Modifier.height(32.dp))

         OutlinedTextField(
             value = clientId,
             onValueChange = { clientId = it },
             label = { Text("Enter Client ID/Name") }
         )

         Spacer(modifier = Modifier.height(12.dp))

         // CHANGED: password is no longer restricted to exactly 5 digits.
         // It can now be any combination of letters, digits, and special
         // characters, with a minimum length of 8 -- so no character-type
         // filtering is applied here, only free text input.
         OutlinedTextField(
             value = password,
             onValueChange = { newValue ->
                 password = newValue
             },
             label = { Text("Enter Password (min 8 characters)") },
             visualTransformation = PasswordVisualTransformation(),
             keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
         )

         if (errorMessage != null) {
             Spacer(modifier = Modifier.height(8.dp))
             Text(
                 text = errorMessage,
                 color = Color.Red,
                 style = MaterialTheme.typography.bodySmall
             )
         }

         Spacer(modifier = Modifier.height(16.dp))

         Button(onClick = {
             if (clientId.isNotBlank() && password.length >= 8) {
                 onRegister(clientId, password)
             }
         }) {
             Text("Register & Enter")
         }
    }
}