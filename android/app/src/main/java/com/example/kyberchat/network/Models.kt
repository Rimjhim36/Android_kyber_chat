package com.example.kyberchat.network

import com.google.gson.annotations.SerializedName

data class RegisterRequest(
    @SerializedName("client_id") val clientId: String,
    @SerializedName("password") val password: String,
    @SerializedName("kyber_public_key") val kyberPublicKey: String,
    @SerializedName("dilithium_public_key") val dilithiumPublicKey: String
)

data class RegisterResponse(
    @SerializedName("ciphertext") val ciphertext: String
)

data class Message(
    @SerializedName("sender_id") val senderId: String,
    @SerializedName("recipient_id") val recipientId: String,
    @SerializedName("content") val content: String,
    @SerializedName("signature") val signature: String,
    @SerializedName("nonce") val nonce: String,
    @SerializedName("kem_ciphertext") val kemCiphertext: String,
    @SerializedName("type") val type: String = "TEXT",
    @SerializedName("msg_id") val msgId: String? = null
)

// Server -> client control frame for sent/delivered/seen updates
data class StatusUpdate(
    @SerializedName("msg_id") val msgId: String,
    @SerializedName("status") val status: String // "sent" | "delivered" | "seen"
)

// NEW: server -> client control frame telling us a message was deleted for everyone.
// Received on the RECIPIENT's device (the sender marks their own copy locally right away).
data class DeleteEvent(
    @SerializedName("msg_id") val msgId: String,
    @SerializedName("sender_id") val senderId: String,
    @SerializedName("recipient_id") val recipientId: String
)

data class KeyResponse(
    @SerializedName("kyber_pk") val kyberPublicKey: String,
    @SerializedName("dilithium_pk") val dilithiumPublicKey: String
)

data class ContactInfo(
    @SerializedName("client_id") val clientId: String,
    @SerializedName("online") val online: Boolean,
    @SerializedName("name") val name: String? = null,
    @SerializedName("picture") val picture: String? = null
)

data class ContactListResponse(
    @SerializedName("contacts") val contacts: List<ContactInfo>
)

data class ProfileRequest(
    @SerializedName("client_id") val clientId: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("picture") val picture: String? = null
)

data class ProfileResponse(
    @SerializedName("client_id") val clientId: String,
    @SerializedName("name") val name: String,
    @SerializedName("picture") val picture: String?,
    // NEW: last-seen timestamp in milliseconds (epoch). Null when the user is
    // currently online, or when they have never gone offline yet.
    @SerializedName("last_seen") val lastSeen: Long? = null
)