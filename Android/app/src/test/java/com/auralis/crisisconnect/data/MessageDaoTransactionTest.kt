package com.auralis.crisisconnect.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageDaoTransactionTest {

    @Database(
        entities = [ContactEntity::class, MessageEntity::class],
        version = 1,
        exportSchema = false
    )
    @TypeConverters(Converters::class)
    abstract class TestDatabase : RoomDatabase() {
        abstract fun contactDao(): ContactDao
        abstract fun messageDao(): MessageDao
    }

    private lateinit var db: TestDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactDao = db.contactDao()
        messageDao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertLocalTextMessageAtomic_updatesSameRowWithoutDuplicate() = runTest {
        val sessionCode = "ABC123"
        val messageUuid = "msg-1"
        contactDao.saveContact(
            ContactEntity(
                sessionCode = sessionCode,
                name = "Test Contact",
                aesKey = "k",
                address = "00:11:22:33:44:55"
            )
        )

        messageDao.upsertLocalTextMessageAtomic(
            sessionCode = sessionCode,
            messageUuid = messageUuid,
            text = "ilk",
            timestampMillis = 1_000L,
            originalTimestampMillis = null,
            deliveryStatus = MessageDeliveryStatus.QUEUED,
            retryCount = 0,
            nextRetryAtMillis = null,
            lastAttemptAtMillis = null,
            lastError = null,
            outboundRoute = null
        )
        messageDao.upsertLocalTextMessageAtomic(
            sessionCode = sessionCode,
            messageUuid = messageUuid,
            text = "guncel",
            timestampMillis = 2_000L,
            originalTimestampMillis = null,
            deliveryStatus = MessageDeliveryStatus.READ,
            retryCount = 2,
            nextRetryAtMillis = 3_000L,
            lastAttemptAtMillis = 2_500L,
            lastError = "x",
            outboundRoute = "rfcomm"
        )

        val rows = messageDao.getRecentMessagesForSession(sessionCode, limit = 10)
        assertEquals(1, rows.size)
        val message = messageDao.getMessageByUuid(messageUuid)
        assertNotNull(message)
        val saved = requireNotNull(message)
        assertEquals("guncel", saved.text)
        assertEquals(MessageDeliveryStatus.READ, saved.deliveryStatus)
        assertEquals(2, saved.retryCount)
        assertTrue(saved.isRead)
    }

    @Test
    fun insert_withoutContact_throwsForeignKeyConstraint() = runTest {
        try {
            messageDao.insert(
                MessageEntity(
                    sessionCode = "UNKNOWN",
                    messageUuid = "orphan-1",
                    text = "x",
                    messageType = MessageType.TEXT,
                    isLocal = true,
                    isRead = false,
                    timestampMillis = 1_000L
                )
            )
            fail("Expected SQLiteConstraintException")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
    }
}
