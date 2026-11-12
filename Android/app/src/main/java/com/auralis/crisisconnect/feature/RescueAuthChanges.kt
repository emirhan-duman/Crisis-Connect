package com.auralis.crisisconnect.feature

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Emits account changes and detaches when the host stops observing. */
internal fun rescueAuthChanges(auth: FirebaseAuth = FirebaseAuth.getInstance()) = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
    auth.addAuthStateListener(listener)
    awaitClose { auth.removeAuthStateListener(listener) }
}.distinctUntilChanged()
