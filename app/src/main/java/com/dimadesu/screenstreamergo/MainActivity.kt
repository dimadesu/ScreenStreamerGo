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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dimadesu.screenstreamergo.databinding.ActivityMainBinding
import com.dimadesu.screenstreamergo.settings.SettingsActivity
import com.dimadesu.screenstreamergo.stream.StreamManager
import com.dimadesu.screenstreamergo.stream.StreamState
import io.github.thibaultbee.streampack.core.streamers.utils.MediaProjectionUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val streamManager by lazy {
        StreamManager.getInstance(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        observeStreamState()
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
                    streamManager.stopStreaming()
                }
            }
        }
    }

    private fun observeStreamState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamManager.state.collect { state ->
                    binding.liveButton.isChecked = (state is StreamState.Streaming)
                    if (state is StreamState.Error) {
                        showAlertDialog(this@MainActivity, "Error", state.message)
                    }
                }
            }
        }
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
            screenCaptureLauncher.launch(
                MediaProjectionUtils.createScreenCaptureIntent(
                    this
                )
            )
        }
    }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                streamManager.startStreaming(
                    resultCode = result.resultCode,
                    resultData = data,
                    onStarted = { moveTaskToBack(true) }
                )
            } else {
                binding.liveButton.isChecked = false
            }
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
