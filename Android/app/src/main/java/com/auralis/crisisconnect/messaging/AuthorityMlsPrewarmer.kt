package com.auralis.crisisconnect.messaging

import android.content.Context
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class AuthorityMlsPrewarmTarget(
    val peerUid: String,
    val scopeType: AuthorityMlsScopeType,
    val channelId: String,
)

/**
 * Publishes this Android device into every reachable 1:1 MLS directory while the normal messages
 * screen is open. This removes the old requirement that both people manually open the same thread:
 * a concurrently active peer can finish KeyPackage/Welcome exchange without user interaction.
 */
object AuthorityMlsPrewarmer {
    private class DeferApplicationUntilChatOpens : Exception()
    private val activePrewarms = ConcurrentHashMap<String, Job>()

    fun yieldToForeground(selfUid: String, binding: AuthorityMlsBinding) {
        activePrewarms.remove(prewarmKey(selfUid, binding))?.cancel()
    }

    suspend fun prewarm(
        context: Context,
        selfUid: String,
        targets: List<AuthorityMlsPrewarmTarget>,
    ) = coroutineScope {
        if (selfUid.isBlank()) return@coroutineScope
        val canonical = targets
            .filter { it.peerUid.isNotBlank() && it.peerUid != selfUid && it.channelId.isNotBlank() }
            .distinctBy { "${it.scopeType.wireName}\u0000${it.channelId}\u0000${it.peerUid}" }
        val permits = Semaphore(4)
        canonical.map { target ->
            async {
                permits.withPermit {
                    val binding = AuthorityMlsBinding(
                        target.scopeType,
                        target.channelId,
                        listOf(selfUid, target.peerUid),
                    )
                    val key = prewarmKey(selfUid, binding)
                    val runningJob = currentCoroutineContext().job
                    activePrewarms[key] = runningJob
                    try {
                        val channel = runCatching {
                            AuthorityMlsChatChannel.prepare(
                                context = context.applicationContext,
                                selfUid = selfUid,
                                peerUid = target.peerUid,
                                scopeType = target.scopeType,
                                channelId = target.channelId,
                                deviceLabel = "Android ${Build.MODEL}".take(64),
                                foreground = false,
                            )
                        }.getOrElse {
                            android.util.Log.w("AuthorityMlsPrewarm", "prepare failed", it)
                            return@withPermit
                        }
                        try {
                            for (attempt in 0 until 20) {
                                val preparation = channel.refreshPreparation()
                                if (preparation.ready) {
                                    // A prewarmer owns no UI/Room transaction. Throwing leaves the
                                    // already-authenticated application in the MLS durable inbox so the
                                    // real thread can persist and acknowledge it exactly once.
                                    channel.activate(
                                        onMessage = { throw DeferApplicationUntilChatOpens() },
                                        onSecurityError = { error ->
                                            if (error !is DeferApplicationUntilChatOpens) {
                                                android.util.Log.w("AuthorityMlsPrewarm", "transport failed", error)
                                            }
                                        },
                                    )
                                    if (channel.isReadyToSend()) break
                                }
                                delay(if (attempt < 10) 400L else 1_500L)
                            }
                        } catch (error: Throwable) {
                            android.util.Log.w("AuthorityMlsPrewarm", "automatic convergence will retry later", error)
                        } finally {
                            // Navigating into a thread cancels this prewarmer. Closing in the cancelled
                            // context can be skipped before the session mutex is acquired, leaking the
                            // per-conversation lease and leaving the real thread disabled forever.
                            withContext(NonCancellable) {
                                runCatching { channel.close() }
                            }
                        }
                    } finally {
                        activePrewarms.remove(key, runningJob)
                    }
                }
            }
        }.awaitAll()
    }

    private fun prewarmKey(selfUid: String, binding: AuthorityMlsBinding): String =
        "$selfUid\u0000${AuthorityMlsIdentifiers.conversationId(binding)}"
}
