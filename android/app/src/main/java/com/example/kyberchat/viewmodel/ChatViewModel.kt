package com.example.kyberchat.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kyberchat.crypto.CryptoManager
import com.example.kyberchat.data.AppDatabase
import com.example.kyberchat.data.MessageEntity
import com.example.kyberchat.data.SessionManager
import com.example.kyberchat.data.UnreadCount
import com.example.kyberchat.network.ApiService
import com.example.kyberchat.network.ChangePasswordRequest
import com.example.kyberchat.network.ContactInfo
import com.example.kyberchat.network.Message
import com.example.kyberchat.network.RegisterRequest
import com.example.kyberchat.network.LogRequest
import com.example.kyberchat.network.ProfileRequest
import com.example.kyberchat.network.ProfileResponse
import com.example.kyberchat.network.WebSocketClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionManager = SessionManager(application)
    private val database = AppDatabase.getDatabase(application)
    private val messageDao = database.messageDao()

    private val _clientId = MutableStateFlow(sessionManager.getClientId() ?: "")
    val clientId = _clientId.asStateFlow()

    val isLoggedIn = _clientId.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private val _profileName = MutableStateFlow("")
    val profileName = _profileName.asStateFlow()

    private val _profilePicture = MutableStateFlow<String?>(null)
    val profilePicture = _profilePicture.asStateFlow()

    private val _profileCache = MutableStateFlow<Map<String, ProfileResponse>>(emptyMap())
    val profileCache = _profileCache.asStateFlow()

    // NEW: result message of the most recent change-password attempt.
    // "SUCCESS" means it went through; any other non-null value is an
    // error message to show the user. Null means no attempt is pending.
    private val _passwordChangeStatus = MutableStateFlow<String?>(null)
    val passwordChangeStatus = _passwordChangeStatus.asStateFlow()

    val contacts = _clientId.flatMapLatest { myId ->
        if (myId.isNotEmpty()) messageDao.getRecentContacts(myId)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val unreadCounts: StateFlow<Map<String, Int>> = _clientId.flatMapLatest { myId ->
        if (myId.isNotEmpty()) {
            messageDao.getUnreadCounts(myId).map { list ->
                list.associate { it.contactId to it.count }
            }
        } else {
            flowOf(emptyMap())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    private val _allUsers = MutableStateFlow<List<ContactInfo>>(emptyList())

    private val _onlineStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val onlineStatus = _onlineStatus.asStateFlow()

    val allUsersWithStatus: StateFlow<List<ContactInfo>> = combine(_allUsers, _onlineStatus) { list, statusMap ->
        list.map { info -> info.copy(online = statusMap[info.clientId] ?: info.online) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _debugLogs = MutableStateFlow<List<String>>(emptyList())
    val debugLogs = _debugLogs.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError = _authError.asStateFlow()

   // private val baseUrl = "http://10.0.2.2:8001"
   // private val wsUrl = "ws://10.0.2.2:8001/ws"
   private val baseUrl = "http://192.168.137.1:8001"
   private val wsUrl = "ws://192.168.137.1:8001/ws"

    private val apiService: ApiService
    private val webSocketClient: WebSocketClient

    val isConnected: StateFlow<Boolean> get() = webSocketClient.isConnected

    init {
        val storedKyber = sessionManager.getKyberPrivKey()
        val storedDilithium = sessionManager.getDilithiumPrivKey()
        if (!storedKyber.isNullOrEmpty() && !storedDilithium.isNullOrEmpty()) {
            try {
                CryptoManager.loadKeys(storedKyber, storedDilithium)
            } catch (e: Exception) {
                e.printStackTrace()
                sessionManager.clearSession()
                _clientId.value = ""
            }
        }

        val okHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(ApiService::class.java)
        webSocketClient = WebSocketClient(okHttpClient)

        if (sessionManager.usedFallbackStorage) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    apiService.sendLog(LogRequest("WARNING: EncryptedSharedPreferences failed to initialize -- using UNENCRYPTED fallback storage. Saved keys from the encrypted store are not visible in this session."))
                } catch (e: Exception) { }
            }
        }

        if (_clientId.value.isNotEmpty()) {
            loadProfile(_clientId.value)
            connectWebSocket(_clientId.value)
        }

        viewModelScope.launch {
            launch {
                webSocketClient.messageFlow.collect { msg ->
                    handleIncomingMessage(msg)
                }
            }
            launch {
                webSocketClient.presenceFlow.collect { update ->
                    _onlineStatus.value = _onlineStatus.value.toMutableMap().apply {
                        put(update.clientId, update.isOnline)
                    }
                }
            }
            launch {
                webSocketClient.presenceListFlow.collect { onlineClients ->
                    val updated = _onlineStatus.value.toMutableMap()
                    updated.keys.forEach { updated[it] = false }
                    onlineClients.forEach { updated[it] = true }
                    _onlineStatus.value = updated
                }
            }
            launch {
                webSocketClient.statusFlow.collect { update ->
                    withContext(Dispatchers.IO) {
                        messageDao.updateStatus(update.msgId, update.status.uppercase())
                    }
                }
            }
            // NEW: applies a "delete for everyone" event received from the other side
            // to our local copy of that message.
            launch {
                webSocketClient.deleteFlow.collect { event ->
                    withContext(Dispatchers.IO) {
                        messageDao.markDeletedByServerId(event.msgId)
                    }
                }
            }
        }
    }

    private fun loadProfile(id: String) {
        _profileName.value = sessionManager.getProfileName(id) ?: id
        _profilePicture.value = sessionManager.getProfilePicture(id)
    }

    fun updateProfile(name: String, pictureBase64: String?) {
        val id = _clientId.value
        if (id.isEmpty()) return
        val finalName = if (name.isBlank()) id else name

        sessionManager.saveProfile(id, finalName, pictureBase64)
        _profileName.value = finalName
        if (pictureBase64 != null) {
            _profilePicture.value = pictureBase64
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = apiService.updateProfile(
                    ProfileRequest(clientId = id, name = finalName, picture = pictureBase64)
                )
                if (response.isSuccessful) {
                    val effectivePicture = pictureBase64 ?: _profilePicture.value
                    _profileCache.value = _profileCache.value + (id to ProfileResponse(id, finalName, effectivePicture))
                } else {
                    log("Profile sync failed: ${response.code()}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                log("Profile sync error: ${e.message}")
            }
        }
    }

    // NEW: changes the logged-in user's password. Validates the new
    // password length locally first (fast feedback), then calls the
    // backend, which re-validates and verifies the current password.
    // Result is published through passwordChangeStatus: "SUCCESS" on
    // success, or a human-readable error message otherwise.
    fun changePassword(currentPassword: String, newPassword: String) {
        val id = _clientId.value
        if (id.isEmpty()) return

        if (newPassword.length < 8) {
            _passwordChangeStatus.value = "New password must be at least 8 characters"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = apiService.changePassword(
                    ChangePasswordRequest(
                        clientId = id,
                        currentPassword = currentPassword,
                        newPassword = newPassword
                    )
                )
                _passwordChangeStatus.value = when {
                    response.isSuccessful -> "SUCCESS"
                    response.code() == 401 -> "Current password is incorrect"
                    response.code() == 400 -> "New password must be at least 8 characters"
                    response.code() == 404 -> "Account not found"
                    else -> "Something went wrong. Try again."
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _passwordChangeStatus.value = "Network error: ${e.message}"
            }
        }
    }

    // NEW: resets the change-password result after the UI has consumed it,
    // so the same message doesn't reappear (e.g. after a screen rotation).
    fun clearPasswordChangeStatus() {
        _passwordChangeStatus.value = null
    }

    // CHANGED: added "forceRefresh" (defaults to false so existing callers like
    // HomeScreen keep their old cache-once behavior). Pass forceRefresh = true
    // when the caller needs up-to-date data (e.g. ChatScreen refreshing "last seen"),
    // since a cached profile can go stale once the other user's online status changes.
    fun fetchProfile(otherClientId: String, forceRefresh: Boolean = false) {
        if (otherClientId.isEmpty()) return
        if (!forceRefresh && _profileCache.value.containsKey(otherClientId)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = apiService.getProfile(otherClientId)
                if (response.isSuccessful && response.body() != null) {
                    val profile = response.body()!!
                    _profileCache.value = _profileCache.value + (otherClientId to profile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun refreshAllUsers() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = apiService.getContacts(_clientId.value)
                if (response.isSuccessful) {
                    _allUsers.value = response.body()?.contacts ?: emptyList()
                } else {
                    log("Failed to load users: ${response.code()}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                log("Users fetch error: ${e.message}")
            }
        }
    }

    fun isContactOnline(otherClientId: String): StateFlow<Boolean> {
        return onlineStatus.map { it[otherClientId] == true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    }

    fun getMessages(contactId: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForContact(_clientId.value, contactId)
    }

    fun markMessagesSeen(contactId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val unseen = messageDao.getUnseenIncoming(_clientId.value, contactId)
            for (m in unseen) {
                if (m.serverMsgId.isNotEmpty()) {
                    webSocketClient.sendSeen(m.serverMsgId, contactId, _clientId.value)
                    messageDao.markAsRead(m.id)
                }
            }
        }
    }

    fun register(newClientId: String, password: String) {
        println("STEP 1: register() called with $newClientId")
        _authError.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val storedKyberPriv = sessionManager.getKyberPrivKey(newClientId)
                val storedDilithiumPriv = sessionManager.getDilithiumPrivKey(newClientId)
                val storedKyberPub = sessionManager.getKyberPubKey(newClientId)
                val storedDilithiumPub = sessionManager.getDilithiumPubKey(newClientId)

                val reuseExistingKeys = !storedKyberPriv.isNullOrEmpty() &&
                        !storedDilithiumPriv.isNullOrEmpty() &&
                        !storedKyberPub.isNullOrEmpty() &&
                        !storedDilithiumPub.isNullOrEmpty()

                val kyberPk: String
                val dilithiumPk: String

                if (reuseExistingKeys) {
                    println("STEP 2: Reusing existing keypair for $newClientId (no regeneration)")
                    CryptoManager.loadKeys(storedKyberPriv!!, storedDilithiumPriv!!)
                    kyberPk = storedKyberPub!!
                    dilithiumPk = storedDilithiumPub!!
                } else {
                    println("STEP 2: Generating new keypair for $newClientId")
                    kyberPk = CryptoManager.generateKyberKeys()
                    dilithiumPk = CryptoManager.generateDilithiumKeys()
                }

                println("STEP 3: Calling backend")

                val response = apiService.register(
                    RegisterRequest(newClientId, password, kyberPk, dilithiumPk)
                )

                println("STEP 4: Response code = ${response.code()}")

                if (response.isSuccessful) {
                    println("STEP 5: Registration successful")

                    val kyberPrivToSave: String
                    val dilithiumPrivToSave: String
                    if (reuseExistingKeys) {
                        kyberPrivToSave = storedKyberPriv!!
                        dilithiumPrivToSave = storedDilithiumPriv!!
                    } else {
                        kyberPrivToSave = CryptoManager.getKyberPrivateKeyB64() ?: ""
                        dilithiumPrivToSave = CryptoManager.getDilithiumPrivateKeyB64() ?: ""
                    }

                    sessionManager.saveSession(
                        newClientId,
                        kyberPrivToSave,
                        dilithiumPrivToSave,
                        kyberPk,
                        dilithiumPk
                    )
                    _clientId.value = newClientId
                    loadProfile(newClientId)

                    println("STEP 6: Connecting websocket")

                    connectWebSocket(newClientId)

                    println("STEP 7: Done")
                } else if (response.code() == 401) {
                    _authError.value = "Incorrect Password"
                    println("Login failed: Incorrect Password")
                } else if (response.code() == 400) {
                    // CHANGED: message now reflects the new 8-character minimum
                    // (password can be numeric, alphabetic, or special characters).
                    _authError.value = "Password must be at least 8 characters"
                    println("Registration failed: Invalid password format")
                } else {
                    _authError.value = "Something went wrong. Try again."
                    println("Registration failed: ${response.code()}")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                _authError.value = "Network error: ${e.message}"
                println("REGISTER ERROR = ${e.message}")
            }
        }
    }

    fun logout() {
        sessionManager.clearSession()
        _clientId.value = ""
        _allUsers.value = emptyList()
        _profileName.value = ""
        _profilePicture.value = null
        webSocketClient.close()
    }

    fun deleteChat(contactId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.deleteChat(_clientId.value, contactId)
        }
    }

    fun deleteSelectedMessages(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.deleteMessagesByIds(ids.toList())
        }
    }

    // NEW: "Delete for everyone" -- only meant to be called for OUR OWN sent messages
    // (ChatScreen already restricts the option to that case). For each selected
    // message we notify the server/recipient over WebSocket, then mark it deleted locally.
    fun deleteForEveryone(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val selectedMessages = messageDao.getMessagesByIds(ids.toList())
            for (m in selectedMessages) {
                if (m.isFromMe && m.serverMsgId.isNotEmpty() && !m.isDeleted) {
                    webSocketClient.sendDeleteForEveryone(m.serverMsgId, _clientId.value, m.recipientId)
                }
            }
            messageDao.markDeletedByIds(ids.toList())
        }
    }

    fun sendMessage(recipientId: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("Sending to $recipientId...")
                val keyResponse = apiService.getKey(recipientId)
                if (!keyResponse.isSuccessful || keyResponse.body() == null) {
                    log("Error: Recipient $recipientId not found")
                    return@launch
                }
                val recipientKyberPk = keyResponse.body()!!.kyberPublicKey

                val (ciphertext, sharedSecret) = CryptoManager.encapsulate(recipientKyberPk)
                val messageAesKey = CryptoManager.deriveAesKey(sharedSecret)
                val (encryptedContent, nonce) = CryptoManager.encrypt(content, messageAesKey)

                val clientIdBytes = _clientId.value.toByteArray(Charsets.UTF_8)
                val recipientIdBytes = recipientId.toByteArray(Charsets.UTF_8)
                val encryptedContentBytes = encryptedContent.toByteArray(Charsets.UTF_8)
                val nonceBytes = nonce.toByteArray(Charsets.UTF_8)
                val ciphertextBytes = ciphertext.toByteArray(Charsets.UTF_8)
                val payloadToSign = clientIdBytes + recipientIdBytes + encryptedContentBytes + nonceBytes + ciphertextBytes
                val signature = CryptoManager.sign(payloadToSign)

                val msgId = UUID.randomUUID().toString()

                val entity = MessageEntity(
                    senderId = _clientId.value,
                    recipientId = recipientId,
                    content = content,
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    messageType = "TEXT",
                    serverMsgId = msgId,
                    status = "SENT"
                )
                messageDao.insertMessage(entity)

                val message = Message(
                    senderId = _clientId.value,
                    recipientId = recipientId,
                    content = encryptedContent,
                    signature = signature,
                    nonce = nonce,
                    kemCiphertext = ciphertext,
                    msgId = msgId
                )
                webSocketClient.sendMessage(message)

            } catch (e: Exception) {
                e.printStackTrace()
                log("Send Error: ${e.message}")
            }
        }
    }

    fun sendImage(recipientId: String, base64Image: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("Sending image to $recipientId...")
                val keyResponse = apiService.getKey(recipientId)
                if (!keyResponse.isSuccessful || keyResponse.body() == null) {
                    log("Error: Recipient $recipientId not found")
                    return@launch
                }
                val recipientKyberPk = keyResponse.body()!!.kyberPublicKey

                val (ciphertext, sharedSecret) = CryptoManager.encapsulate(recipientKyberPk)
                val messageAesKey = CryptoManager.deriveAesKey(sharedSecret)
                val (encryptedContent, nonce) = CryptoManager.encrypt(base64Image, messageAesKey)

                val clientIdBytes = _clientId.value.toByteArray(Charsets.UTF_8)
                val recipientIdBytes = recipientId.toByteArray(Charsets.UTF_8)
                val encryptedContentBytes = encryptedContent.toByteArray(Charsets.UTF_8)
                val nonceBytes = nonce.toByteArray(Charsets.UTF_8)
                val ciphertextBytes = ciphertext.toByteArray(Charsets.UTF_8)
                val payloadToSign = clientIdBytes + recipientIdBytes + encryptedContentBytes + nonceBytes + ciphertextBytes
                val signature = CryptoManager.sign(payloadToSign)

                val msgId = UUID.randomUUID().toString()

                val entity = MessageEntity(
                    senderId = _clientId.value,
                    recipientId = recipientId,
                    content = base64Image,
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true,
                    messageType = "IMAGE",
                    serverMsgId = msgId,
                    status = "SENT"
                )
                messageDao.insertMessage(entity)

                val message = Message(
                    senderId = _clientId.value,
                    recipientId = recipientId,
                    content = encryptedContent,
                    signature = signature,
                    nonce = nonce,
                    kemCiphertext = ciphertext,
                    type = "IMAGE",
                    msgId = msgId
                )
                webSocketClient.sendMessage(message)

            } catch (e: Exception) {
                e.printStackTrace()
                log("Send Image Error: ${e.message}")
            }
        }
    }

    private fun handleIncomingMessage(msg: Message) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sharedSecret = CryptoManager.decapsulate(msg.kemCiphertext)
                val messageAesKey = CryptoManager.deriveAesKey(sharedSecret)
                val decryptedContent = CryptoManager.decrypt(msg.content, msg.nonce, messageAesKey)

                val entity = MessageEntity(
                    senderId = msg.senderId,
                    recipientId = _clientId.value,
                    content = decryptedContent,
                    timestamp = System.currentTimeMillis(),
                    isFromMe = false,
                    messageType = msg.type,
                    serverMsgId = msg.msgId ?: ""
                )
                messageDao.insertMessage(entity)

            } catch (e: Exception) {
                e.printStackTrace()
                log("Decrypt Error: ${e.message}")
                try {
                    apiService.sendLog(LogRequest("DECRYPT_ERROR [$_clientId -> local]: ${e.message}\nStack: ${e.stackTraceToString()}"))
                } catch (e2: Exception) { }
            }
        }
    }

    private fun connectWebSocket(id: String) {
        webSocketClient.connect("$wsUrl/$id")
    }

    private fun log(msg: String) {
        println("KyberChat: $msg")
        _debugLogs.value = _debugLogs.value + msg
    }
}