package com.auralis.crisisconnect.data.database

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DatabaseInitializer(
    private val auth: FirebaseAuth = FirebaseUtils.auth,
    private val db: FirebaseFirestore = FirebaseUtils.db
) {
    fun initializeDatabase(context: Context) {
        LocalKeyStorage.getOrCreateAesKey(context)
        val savedUid = LocalKeyStorage.getSavedUid(context)
        val currentUser = auth.currentUser

        if (currentUser != null && !currentUser.isAnonymous) {
            LocalKeyStorage.saveUid(context, currentUser.uid)
            CoroutineScope(Dispatchers.Main).launch {
                setupCountryProfile(context, currentUser.uid, savedUid)
            }
            return
        }

        if (currentUser?.isAnonymous == true) {
            auth.signOut()
            LocalKeyStorage.clearUid(context)
            Log.i(
                TAG,
                "Cleared persisted anonymous Firebase session. App now continues in local-only mode until explicit sign-in."
            )
            return
        }

        if (!hasNetworkConnection(context)) {
            Log.i(
                TAG,
                "No network connection. Skipping Firebase bootstrap and continuing in local-only mode"
            )
            return
        }

        Log.i(
            TAG,
            "No authenticated Firebase user. Skipping Firebase bootstrap and continuing in local-only mode"
        )
    }

    private suspend fun setupCountryProfile(
        context: Context,
        uid: String,
        savedUid: String?
    ) = withContext(Dispatchers.IO) {
        val countryCode = localeCountryCode()

        LocalKeyStorage.saveCountry(context, countryCode)

        if (savedUid == null || savedUid != uid) {
            val userData = hashMapOf(
                "id" to uid,
                "platform" to "android",
                "role" to "user",
                "email" to "",
                "username" to "",
                "verified" to false,
                "country" to countryCode,
                "createdAt" to FieldValue.serverTimestamp()
            )

            db.collection("users").document(uid).set(userData)
                .addOnSuccessListener {
                    LocalKeyStorage.saveUid(context, uid)
                    Log.d(TAG, "✅ User saved with country=$countryCode")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to save user", e)
                }
        } else {
            val updates = hashMapOf<String, Any>(
                "country" to countryCode,
                "countrySource" to FieldValue.delete(),
                "vpnActive" to FieldValue.delete()
            )
            db.collection("users").document(uid).update(updates)
        }
    }

    private fun localeCountryCode(): String {
        val locale = Locale.getDefault()
        return if (locale.country.isBlank()) {
            locale.language.uppercase(Locale.US)
        } else {
            locale.country.uppercase(Locale.US)
        }
    }

    private fun hasNetworkConnection(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    companion object { private const val TAG = "DCS_DB" }
}
