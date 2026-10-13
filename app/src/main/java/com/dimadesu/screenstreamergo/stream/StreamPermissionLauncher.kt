/*
 * Copyright (C) 2026 Dmytro Antonov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.screenstreamergo.stream

import android.Manifest
import android.app.Activity
import android.os.Build
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.dimadesu.screenstreamergo.utils.showPermissionAlertDialog
import io.github.thibaultbee.streampack.core.streamers.utils.MediaProjectionUtils

/**
 * Handles the two-stage permission flow for screen streaming:
 * 1. Checks and requests the [Manifest.permission.RECORD_AUDIO] permission.
 * 2. Launches the Android MediaProjection consent dialog upon audio permission approval.
 */
class StreamPermissionLauncher(
    activity: ComponentActivity,
    private val onConsentGranted: (resultCode: Int, data: Intent) -> Unit,
    private val onConsentDenied: () -> Unit
) {
    private val screenCaptureLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            onConsentGranted(result.resultCode, data)
        } else {
            onConsentDenied()
        }
    }

    private val audioPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            screenCaptureLauncher.launch(
                MediaProjectionUtils.createScreenCaptureIntent(activity)
            )
        } else {
            activity.showPermissionAlertDialog { activity.finish() }
            onConsentDenied()
        }
    }

    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* silently proceed whether granted or not */ }

    /**
     * Requests the notification permission (Android 13+). Proceeds silently if denied.
     */
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Initiates the audio permission check, chaining into the screen capture prompt.
     */
    fun launch() {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
