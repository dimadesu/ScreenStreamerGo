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
import com.dimadesu.screenstreamergo.stream.StreamManager
import com.dimadesu.screenstreamergo.stream.StreamState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-off UI events emitted by [MainViewModel].
 */
sealed interface MainEvent {
    /** Streaming started successfully; the UI may move itself to the background. */
    object StreamStarted : MainEvent
}

/**
 * Bridges the UI ([MainActivity]) and the core streaming logic ([StreamManager]).
 * Contains no Android UI or permission code.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val streamManager = StreamManager.getInstance(application)

    val state: StateFlow<StreamState> = streamManager.state

    private val _events = MutableSharedFlow<MainEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    fun startStreaming(resultCode: Int, resultData: Intent) {
        streamManager.startStreaming(
            resultCode = resultCode,
            resultData = resultData,
            onStarted = { _events.tryEmit(MainEvent.StreamStarted) }
        )
    }

    fun stopStreaming() = streamManager.stopStreaming()
}
