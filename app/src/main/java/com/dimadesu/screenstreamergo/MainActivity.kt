/*
 * Copyright (C) 2021 Thibault B.
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
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dimadesu.screenstreamergo.databinding.ActivityMainBinding
import com.dimadesu.screenstreamergo.services.DemoMediaProjectionService
import com.dimadesu.screenstreamergo.settings.SettingsActivity
import com.dimadesu.screenstreamergo.stream.StreamCoordinator
import io.github.thibaultbee.streampack.core.streamers.IVideoStreamer
import io.github.thibaultbee.streampack.core.streamers.utils.MediaProjectionUtils
import io.github.thibaultbee.streampack.services.MediaProjectionService
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val streamCoordinator by lazy {
        StreamCoordinator(this)
    }

    private var connection: ServiceConnection? = null

    private var streamer: IVideoStreamer<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()

        binding.actions.setOnClickListener {
            showPopup()
        }

        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) {
                    requestAudioPermissionsLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    lifecycleScope.launch {
                        streamCoordinator.stopStream(streamer)
                    }
                    connection?.let { unbindService(it) }
                    connection = null
                    val intent = Intent(this@MainActivity, DemoMediaProjectionService::class.java)
                    stopService(intent)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connection?.let { unbindService(it) }
        connection = null
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* silently proceed whether granted or not */ }

    private val requestAudioPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionAlertDialog(this) { this.finish() }
        } else {
            getContent.launch(
                MediaProjectionUtils.createScreenCaptureIntent(
                    this
                )
            )
        }
    }

    private var getContent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val intent = Intent(this, DemoMediaProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            connection = MediaProjectionService.bindService(
                context = this,
                serviceClass = DemoMediaProjectionService::class.java,
                resultCode = result.resultCode,
                resultData = result.data!!,
                onServiceCreated = { streamer ->
                    streamer as IVideoStreamer<*> // Cast to streamer type
                    lifecycleScope.launch {
                        try {
                            streamCoordinator.configure(streamer)
                            streamCoordinator.startStream(streamer)
                            moveTaskToBack(true)
                        } catch (t: Throwable) {
                            this@MainActivity.showAlertDialog(
                                this@MainActivity, "Error", t.message ?: "Unknown error"
                            )
                            binding.liveButton.isChecked = false
                            Log.e(TAG, "Error while starting streamer", t)
                        }
                    }
                    lifecycleScope.launch {
                        streamer.isStreamingFlow.collect { isStreaming ->
                            binding.liveButton.isChecked = isStreaming
                        }
                    }
                    lifecycleScope.launch {
                        streamer.isStreamingFlow.drop(1).collect { isStreaming ->
                            if (!isStreaming) {
                                // Clean up so the next start gets a fresh service & MediaProjection.
                                connection?.let { unbindService(it) }
                                connection = null
                                this@MainActivity.streamer = null
                            }
                        }
                    }
                    this.streamer = streamer
                },
                onServiceDisconnected = {
                    streamer = null
                    Log.i(TAG, "Service disconnected")
                },
                onExtra = { extra ->
                    streamCoordinator.prepareServiceExtras(extra)
                }
            )
        }

    private fun showPopup() {
        val popup = PopupMenu(this, binding.actions)
        val inflater: MenuInflater = popup.menuInflater
        inflater.inflate(R.menu.actions, popup.menu)
        popup.show()
        popup.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                goToSettingsActivity()
            } else {
                Log.e(TAG, "Unknown menu item ${it.itemId}")
            }
            true
        }
    }

    private fun goToSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.actions, menu)
        return true
    }

    private fun showAlertDialog(
        context: Context, title: String, message: String, afterPositiveButton: () -> Unit = {}
    ) {
        AlertDialog.Builder(context).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok) { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.dismiss()
                afterPositiveButton()
            }.show()
    }

    private fun showAlertDialog(
        context: Context,
        titleResourceId: Int,
        messageResourceId: Int,
        afterPositiveButton: () -> Unit = {}
    ) {
        AlertDialog.Builder(context).setTitle(titleResourceId).setMessage(messageResourceId)
            .setPositiveButton(android.R.string.ok) { dialogInterface: DialogInterface, _: Int ->
                dialogInterface.dismiss()
                afterPositiveButton()
            }.show()
    }

    private fun showPermissionAlertDialog(
        context: Context, afterPositiveButton: () -> Unit = {}
    ) = showAlertDialog(
        context, R.string.permission, R.string.permission_not_granted, afterPositiveButton
    )

    companion object {
        private const val TAG = "MainActivity"
    }
}
