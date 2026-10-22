package com.auralis.crisisconnect.verification

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.auralis.crisisconnect.messaging.DeviceContactsReader

/** Internal-build harness for the real system picker; absent from production releases. */
class ContactPickerVerificationActivity : ComponentActivity() {
    @Volatile var resultCode: Int? = null
        private set
    @Volatile var selectedUri: Uri? = null
        private set
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        selectedUri = it.data?.data
        resultCode = it.resultCode
    }

    fun pickPhone() {
        resultCode = null
        selectedUri = null
        picker.launch(DeviceContactsReader.phonePickerIntent())
    }
}
