package com.auralis.crisisconnect.messaging

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsPickerSessionContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SelectedContactReaderTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val selectedUri = Uri.parse("content://selected-contact/phone/42")
    private lateinit var provider: SelectedProvider

    @Before
    fun setup() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        provider = SelectedProvider()
        ShadowContentResolver.registerProviderInternal("selected-contact", provider)
    }

    @Test
    fun `Android 17 requests only one phone field while older devices use phone picker`() {
        val legacy = DeviceContactsReader.phonePickerIntent(36)
        assertEquals(Intent.ACTION_PICK, legacy.action)
        assertEquals(Phone.CONTENT_TYPE, legacy.type)
        val modern = DeviceContactsReader.phonePickerIntent(37)
        assertEquals(ContactsPickerSessionContract.ACTION_PICK_CONTACTS, modern.action)
        assertEquals(
            arrayListOf(Phone.CONTENT_ITEM_TYPE),
            modern.getStringArrayListExtra(ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS)
        )
        assertFalse(modern.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        assertEquals(1, modern.getIntExtra(ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT, 0))
    }

    @Test
    fun `selection reads only shared URI without broad contact permission`() {
        provider.rows = listOf(arrayOf(" Alice ", "+1 202 555 0123"))
        assertEquals(DeviceContact("Alice", "+12025550123"), DeviceContactsReader.readSelected(app, selectedUri))
        assertEquals(listOf(selectedUri), provider.queries)
        assertTrue(provider.closed)
    }

    @Test
    fun `missing name falls back to number and empty selection is ignored`() {
        provider.rows = listOf(arrayOf(null, "+1 202 555 0123"))
        assertEquals("+12025550123", DeviceContactsReader.readSelected(app, selectedUri)?.displayName)
        provider.rows = emptyList()
        assertNull(DeviceContactsReader.readSelected(app, selectedUri))
    }

    @Test
    fun `invalid number is ignored and non content URI never reaches provider`() {
        provider.rows = listOf(arrayOf("Alice", "not a number"))
        assertNull(DeviceContactsReader.readSelected(app, selectedUri))
        provider.queries.clear()
        assertThrows(IllegalArgumentException::class.java) {
            DeviceContactsReader.readSelected(app, Uri.parse("https://example.com/contact"))
        }
        assertTrue(provider.queries.isEmpty())
    }

    private class SelectedProvider : ContentProvider() {
        var rows: List<Array<String?>> = emptyList()
        val queries = mutableListOf<Uri>()
        var closed = false
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries += uri
            assertNull(selection)
            assertNull(selectionArgs)
            assertArrayEquals(arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), projection)
            return object : MatrixCursor(arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER)) {
                override fun close() { closed = true; super.close() }
            }.apply { rows.forEach { addRow(it) } }
        }
        override fun getType(uri: Uri) = Phone.CONTENT_ITEM_TYPE
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
