package com.auralis.crisisconnect.messaging

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.mockk
import io.mockk.Called
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test

class HierarchyMlsPreviewTest {
    @Test fun `MLS preview never queries retired ciphertext logs`() = runBlocking {
        val firestore = mockk<FirebaseFirestore>()
        val auth = mockk<FirebaseAuth>()
        val client = HierarchyMessagingClient(firestore, auth)
        assertNull(client.latestMessageWith("channel", "peer"))
        verify { firestore wasNot Called }
        verify { auth wasNot Called }
    }
}
