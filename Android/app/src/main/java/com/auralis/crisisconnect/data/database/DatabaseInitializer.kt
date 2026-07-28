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
            // Keep anonymous sessions — they are a valid QR-only internet-messaging identity
            // (created by MessagingBootstrap so a QR-added contact can be reached online without an
            // explicit login). We must NOT sign out here, otherwise the identity key never publishes
            // and internet messaging stays unavailable. Real-account setup (country profile, uid
            // mapping) is intentionally skipped for anonymous users; every real-account feature
            // already gates on !isAnonymous.
            Log.i(TAG, "Retaining anonymous Firebase session for internet messaging.")
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
            // A stale/missing local uid does NOT mean the cloud doc is new: a reinstall or
            // account switch lands here with a fully populated users/{uid}. Replacing it
            // (set without merge) nuked profile fields — including username, which web and
            // iOS also write — and rules even reject the replace for panel accounts because
            // it resets role/verified. Only seed the skeleton when the doc truly does not exist.
            val docRef = db.collection("users").document(uid)
            docRef.get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.exists()) {
                        LocalKeyStorage.saveUid(context, uid)
                        docRef.update(
                            hashMapOf<String, Any>(
                                "country" to countryCode,
                                "countrySource" to FieldValue.delete(),
                                "vpnActive" to FieldValue.delete()
                            )
                        )
                        return@addOnSuccessListener
                    }
                    val userData = hashMapOf(
                        "id" to uid,
                        "platform" to "android",
                        "role" to "user",
                        "verified" to false,
                        "country" to countryCode,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    docRef.set(userData)
                        .addOnSuccessListener {
                            LocalKeyStorage.saveUid(context, uid)
                            Log.d(TAG, "✅ User saved with country=$countryCode")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "❌ Failed to save user", e)
                        }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to load user before bootstrap", e)
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
