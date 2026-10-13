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

import android.content.Intent
import android.os.Bundle
import android.widget.PopupMenu
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dimadesu.screenstreamergo.databinding.ActivityMainBinding
import com.dimadesu.screenstreamergo.settings.SettingsActivity
import com.dimadesu.screenstreamergo.stream.StreamState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val permissions = StreamPermissionRequester(this) { result ->
        when (result) {
            is StreamPermissionResult.Granted ->
                viewModel.startStreaming(result.resultCode, result.data)

            StreamPermissionResult.AudioDenied -> {
                binding.liveButton.isChecked = false
                showDialog(
                    getString(R.string.permission),
                    getString(R.string.permission_not_granted)
                ) { finish() }
            }

            StreamPermissionResult.ScreenCaptureDenied ->
                binding.liveButton.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissions.requestNotificationPermission()

        binding.actions.setOnClickListener {
            showPopup()
        }

        binding.liveButton.setOnCheckedChangeListener { view, isChecked ->
            if (view.isPressed) {
                if (isChecked) {
                    permissions.request()
                } else {
                    viewModel.stopStreaming()
                }
            }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        binding.liveButton.isChecked = state != StreamState.Idle
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            MainEvent.StreamStarted -> moveTaskToBack(true)
                            is MainEvent.Error -> showDialog("Error", event.message)
                        }
                    }
                }
            }
        }
    }

    private fun showDialog(title: String, message: String, onConfirm: () -> Unit = {}) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .show()
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
