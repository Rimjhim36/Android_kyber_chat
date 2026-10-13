package com.example.kyberchat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {
    // Exposed so callers (e.g. ChatViewModel) can detect and log when the
    // Keystore-backed encrypted store failed to initialize. Silently falling
    // back to a DIFFERENT preferences file means keys saved under the
    // encrypted store become invisible after a fallback, and vice versa --
    // this was the actual root cause of Client repeatedly getting a fresh
    // keypair even though per-client_id key reuse was implemented correctly.
    var usedFallbackStorage: Boolean = false
        private set

    private val sharedPreferences: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secret_shared_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        usedFallbackStorage = true
        e.printStackTrace()
        context.getSharedPreferences("secret_shared_prefs_fallback", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"

        // Keys are stored PER client_id instead of in one shared slot.
        // Previously every account on this device (AA, BB,
        // Client...) overwrote the same "kyber_priv"/"dilithium_priv" entries,
        // so switching accounts or logging out and back in destroyed the
        // previous account's crypto identity and made old messages
        // undecryptable (AEADBadTagException).
        private const val PREFIX_KYBER_PRIV = "kyber_priv_"
        private const val PREFIX_DILITHIUM_PRIV = "dilithium_priv_"
        private const val PREFIX_KYBER_PUB = "kyber_pub_"
        private const val PREFIX_DILITHIUM_PUB = "dilithium_pub_"

        // NEW: per-client_id profile info (display name + profile picture as Base64 JPEG)
        private const val PREFIX_PROFILE_NAME = "profile_name_"
        private const val PREFIX_PROFILE_PICTURE = "profile_picture_"
    }

    /**
     * Saves the crypto keys for [clientId] (keyed by that id, so other accounts'
     * keys on this device are untouched) and marks [clientId] as the currently
     * active session.
     */
    fun saveSession(
        clientId: String,
        kyberPrivB64: String,
        dilithiumPrivB64: String,
        kyberPubB64: String,
        dilithiumPubB64: String
    ) {
        sharedPreferences.edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(PREFIX_KYBER_PRIV + clientId, kyberPrivB64)
            .putString(PREFIX_DILITHIUM_PRIV + clientId, dilithiumPrivB64)
            .putString(PREFIX_KYBER_PUB + clientId, kyberPubB64)
            .putString(PREFIX_DILITHIUM_PUB + clientId, dilithiumPubB64)
            .apply()
    }

    fun getClientId(): String? {
        return sharedPreferences.getString(KEY_CLIENT_ID, null)
    }


    fun getKyberPrivKey(clientId: String? = getClientId()): String? {
        if (clientId == null) return null
        return sharedPreferences.getString(PREFIX_KYBER_PRIV + clientId, null)
    }

    fun getDilithiumPrivKey(clientId: String? = getClientId()): String? {
        if (clientId == null) return null
        return sharedPreferences.getString(PREFIX_DILITHIUM_PRIV + clientId, null)
    }

    fun getKyberPubKey(clientId: String? = getClientId()): String? {
        if (clientId == null) return null
        return sharedPreferences.getString(PREFIX_KYBER_PUB + clientId, null)
    }

    fun getDilithiumPubKey(clientId: String? = getClientId()): String? {
        if (clientId == null) return null
        return sharedPreferences.getString(PREFIX_DILITHIUM_PUB + clientId, null)
    }

    fun clearSession() {
        sharedPreferences.edit().remove(KEY_CLIENT_ID).apply()
    }


    fun forgetAccount(clientId: String) {
        sharedPreferences.edit()
            .remove(PREFIX_KYBER_PRIV + clientId)
            .remove(PREFIX_DILITHIUM_PRIV + clientId)
            .remove(PREFIX_KYBER_PUB + clientId)
            .remove(PREFIX_DILITHIUM_PUB + clientId)
            .remove(PREFIX_PROFILE_NAME + clientId)     // NEW: also wipe profile data
            .remove(PREFIX_PROFILE_PICTURE + clientId)  // NEW
            .apply()
    }


    fun saveProfile(clientId: String, name: String, pictureBase64: String?) {
        val editor = sharedPreferences.edit()
        editor.putString(PREFIX_PROFILE_NAME + clientId, name)
        if (pictureBase64 != null) {
            editor.putString(PREFIX_PROFILE_PICTURE + clientId, pictureBase64)
        }
        editor.apply()
    }

    // returns saved display name for a client_id, or null if never set.
    fun getProfileName(clientId: String? = getClientId()): String? {
        if (clientId == null) return null
        return sharedPreferences.getString(PREFIX_PROFILE_NAME + clientId, null)
    }

    // returns saved profile picture (Base64 JPEG string) for a client_id, or null.
    fun getProfilePicture(clientId: String? = getClientId()): String? {
        if (clientId == null) return null
        return sharedPreferences.getString(PREFIX_PROFILE_PICTURE + clientId, null)
    }
}