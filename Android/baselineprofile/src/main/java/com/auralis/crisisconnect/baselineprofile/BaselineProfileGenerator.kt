package com.auralis.crisisconnect.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates baseline-prof.txt for the app's hottest path: cold start into MainScreen.
 *
 * Run with a device (API 33+ unrooted, e.g. the S21) connected:
 *   ./gradlew :app:generateBaselineProfile
 *
 * The output is copied into app/src/release/generated/baselineProfiles/ automatically and ships
 * in the next release build; profileinstaller AOT-compiles it on install.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generateStartupProfile() {
        rule.collect(
            packageName = "com.auralis.crisisconnect",
            includeInStartupProfile = true
        ) {
            pressHome()
            // Cold start through splash + MainActivity + MainScreen composition.
            startActivityAndWait()
            device.waitForIdle()
        }
    }
}
