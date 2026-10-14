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

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dimadesu.screenstreamergo.stream.StreamController
import com.dimadesu.screenstreamergo.stream.StreamState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * One-off UI events emitted by [MainViewModel].
 */
sealed interface MainEvent {
    /** Streaming started successfully; the UI may move itself to the background. */
    object StreamStarted : MainEvent

    /** Something went wrong; shown once. */
    data class Error(val message: String) : MainEvent
}

/**
 * Bridges the UI ([MainActivity]) and the core streaming logic ([StreamController]).
 * Contains no Android UI or permission code.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = StreamController.getInstance(application)

    val state: StateFlow<StreamState> = controller.state

    // Channel (not SharedFlow) so events raised while the UI is in the background
    // are delivered when it comes back, exactly once.
    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events: Flow<MainEvent> = _events.receiveAsFlow()

    private var awaitingStart = false

    init {
        viewModelScope.launch {
            state.collect { state ->
                if (state is StreamState.Streaming && awaitingStart) {
                    awaitingStart = false
                    _events.send(MainEvent.StreamStarted)
                }
            }
        }
        viewModelScope.launch {
            controller.errors.collect { message ->
                awaitingStart = false
                _events.send(MainEvent.Error(message))
            }
        }
    }

    fun startStreaming(resultCode: Int, resultData: Intent) {
        awaitingStart = true
        controller.start(resultCode, resultData)
    }

    fun stopStreaming() = controller.stop()
}
