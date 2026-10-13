package com.example.kyberchat.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.Channel  
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow

data class PresenceUpdate(val clientId: String, val isOnline: Boolean)

class WebSocketClient(private val client: OkHttpClient) {

    private var webSocket: WebSocket? = null
    private val gson = Gson()

    private val _messageChannel = Channel<Message>()
    val messageFlow = _messageChannel.receiveAsFlow()

    private val _logChannel = Channel<String>()
    val logFlow = _logChannel.receiveAsFlow()

    private val _presenceChannel = Channel<PresenceUpdate>()
    val presenceFlow = _presenceChannel.receiveAsFlow()

    private val _presenceListChannel = Channel<List<String>>()
    val presenceListFlow = _presenceListChannel.receiveAsFlow()

    private val _statusChannel = Channel<StatusUpdate>()
    val statusFlow = _statusChannel.receiveAsFlow()

    // NEW: fired when the OTHER side deletes a message for everyone
    private val _deleteChannel = Channel<DeleteEvent>()
    val deleteFlow = _deleteChannel.receiveAsFlow()

    private var currentUrl: String? = null
    @Volatile private var shouldReconnect = true
    @Volatile private var isReconnecting = false

    private fun log(msg: String) {
        _logChannel.trySend(msg)
        println("WebSocketClient: $msg")
    }

    private val _isConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    fun connect(url: String) {
        if (url.isEmpty()) return
        currentUrl = url
        shouldReconnect = true

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                log("WebSocket Connected")
                _isConnected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                try {
                    val jsonObject = JsonParser.parseString(text).asJsonObject
                    val type = if (jsonObject.has("type")) jsonObject.get("type").asString else null

                    when (type) {
                        "presence" -> {
                            val clientId = jsonObject.get("client_id").asString
                            val status = jsonObject.get("status").asString
                            _presenceChannel.trySend(PresenceUpdate(clientId, status == "online"))
                        }
                        "presence_list" -> {
                            val arr = jsonObject.getAsJsonArray("online_clients")
                            val list = arr.map { it.asString }
                            _presenceListChannel.trySend(list)
                        }
                        "status_update" -> {
                            val statusUpdate = gson.fromJson(text, StatusUpdate::class.java)
                            _statusChannel.trySend(statusUpdate)
                        }
                        // NEW: the other side deleted a message for everyone
                        "delete_for_everyone" -> {
                            val deleteEvent = gson.fromJson(text, DeleteEvent::class.java)
                            _deleteChannel.trySend(deleteEvent)
                        }
                        else -> {
                            val message = gson.fromJson(text, Message::class.java)
                            _messageChannel.trySend(message)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    log("JSON Parse Error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                log("WebSocket Closing: $reason")
                _isConnected.value = false
                attemptReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                t.printStackTrace()
                log("WebSocket Failure: ${t.message}")
                _isConnected.value = false
                attemptReconnect()
            }
        })
    }

    private fun attemptReconnect() {
        if (!shouldReconnect || currentUrl == null || isReconnecting) return
        isReconnecting = true

        log("Reconnecting in 3s...")
        Thread {
            try {
                Thread.sleep(3000)
                if (shouldReconnect) {
                    val url = currentUrl
                    if (url != null) {
                        log("Attempting Reconnect...")
                        connect(url)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isReconnecting = false
            }
        }.start()
    }

    fun sendMessage(message: Message) {
        val json = gson.toJson(message)
        webSocket?.send(json)
    }

    fun sendSeen(msgId: String, senderId: String, viewerId: String): Boolean {
        val payload = mapOf(
            "type" to "seen",
            "msg_id" to msgId,
            "sender_id" to senderId,
            "viewer_id" to viewerId
        )
        return webSocket?.send(gson.toJson(payload)) ?: false
    }

    // NEW: notifies the server (and, through it, the recipient) that this
    // message should be deleted for everyone. Only call this for OUR OWN sent messages.
    fun sendDeleteForEveryone(msgId: String, senderId: String, recipientId: String): Boolean {
        val payload = mapOf(
            "type" to "delete_for_everyone",
            "msg_id" to msgId,
            "sender_id" to senderId,
            "recipient_id" to recipientId
        )
        return webSocket?.send(gson.toJson(payload)) ?: false
    }

    fun close() {
        shouldReconnect = false
        webSocket?.close(1000, "Goodbye")
    }
}