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
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dimadesu.screenstreamergo.databinding.ActivityMainBinding
import com.dimadesu.screenstreamergo.settings.SettingsActivity
import com.dimadesu.screenstreamergo.stream.StreamManager
import com.dimadesu.screenstreamergo.stream.StreamPermissionLauncher
import com.dimadesu.screenstreamergo.stream.StreamState
import com.dimadesu.screenstreamergo.utils.showAlertDialog
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val streamManager by lazy {
        StreamManager.getInstance(this)
    }

    private val streamLauncher = StreamPermissionLauncher(
        activity = this,
        onConsentGranted = { resultCode, data ->
            streamManager.startStreaming(
                resultCode = resultCode,
                resultData = data,
                onStarted = { moveTaskToBack(true) }
            )
        },
        onConsentDenied = {
            binding.liveButton.isChecked = false
        }
    )

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* silently proceed whether granted or not */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.actions.setOnClickListener {
            showPopup()
        }

        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) {
                    streamLauncher.launch()
                } else {
                    streamManager.stopStreaming()
                }
            }
        }

        observeStreamState()
    }

    private fun observeStreamState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamManager.state.collect { state ->
                    binding.liveButton.isChecked = (state is StreamState.Streaming)
                    if (state is StreamState.Error) {
                        showAlertDialog("Error", state.message)
                    }
                }
            }
        }
    }

    private fun showPopup() {
        PopupMenu(this, binding.actions).apply {
            menuInflater.inflate(R.menu.actions, menu)
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == R.id.action_settings) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    true
                } else {
                    false
                }
            }
            show()
        }
    }
}
