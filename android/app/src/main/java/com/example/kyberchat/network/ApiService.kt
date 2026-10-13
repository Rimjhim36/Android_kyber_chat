package com.example.kyberchat.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @GET("/key/{clientId}")
    suspend fun getKey(@Path("clientId") clientId: String): Response<KeyResponse>

    @POST("/log")
    suspend fun sendLog(@Body request: LogRequest): Response<Unit>

    @GET("/contacts")
    suspend fun getContacts(@Query("exclude") excludeId: String): Response<ContactListResponse>

    // NEW: uploads/updates the logged-in user's display name and profile picture.
    @POST("/profile")
    suspend fun updateProfile(@Body request: ProfileRequest): Response<Unit>

    // NEW: fetches another (or your own) user's display name + profile picture.
    @GET("/profile/{clientId}")
    suspend fun getProfile(@Path("clientId") clientId: String): Response<ProfileResponse>

    // NEW: changes the logged-in user's password. Server verifies
    // currentPassword before applying newPassword.
    @POST("/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Response<Unit>
}

data class LogRequest(val message: String)

// NEW: request body for POST /change-password.
data class ChangePasswordRequest(
    @SerializedName("client_id") val clientId: String,
    @SerializedName("current_password") val currentPassword: String,
    @SerializedName("new_password") val newPassword: String
)