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
package com.dimadesu.screenstreamergo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.thibaultbee.streampack.core.streamers.utils.MediaProjectionUtils

/**
 * Outcome of [StreamPermissionRequester.request].
 */
sealed interface StreamPermissionResult {
    /** Both RECORD_AUDIO and MediaProjection consent were granted. */
    data class Granted(val resultCode: Int, val data: Intent) : StreamPermissionResult

    /** The user denied RECORD_AUDIO. */
    object AudioDenied : StreamPermissionResult

    /** The user dismissed the MediaProjection consent dialog. */
    object ScreenCaptureDenied : StreamPermissionResult
}

/**
 * Handles the two-stage permission flow for screen streaming:
 * 1. Checks and requests the [Manifest.permission.RECORD_AUDIO] permission.
 * 2. Launches the Android MediaProjection consent dialog upon audio permission approval.
 *
 * Only reports the outcome; how to react (dialogs, closing the screen) is up to the caller.
 * Must be constructed before the activity reaches STARTED (e.g. as a property initializer).
 */
class StreamPermissionRequester(
    activity: ComponentActivity,
    private val onResult: (StreamPermissionResult) -> Unit
) {
    private val screenCaptureLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            onResult(StreamPermissionResult.Granted(result.resultCode, data))
        } else {
            onResult(StreamPermissionResult.ScreenCaptureDenied)
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
            onResult(StreamPermissionResult.AudioDenied)
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
    fun request() {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
