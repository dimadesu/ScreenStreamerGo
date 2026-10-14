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
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dimadesu.screenstreamergo.models.Actions
import com.dimadesu.screenstreamergo.services.DemoMediaProjectionService
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.IConfigurableAudioStreamer
import io.github.thibaultbee.streampack.core.streamers.IVideoStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerAudioConfig
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerVideoConfig
import io.github.thibaultbee.streampack.core.streamers.dual.IAudioDualStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.IDualStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.IVideoDualStreamer
import io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer
import io.github.thibaultbee.streampack.services.MediaProjectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Represents the observable lifecycle state of the streaming session.
 */
sealed interface StreamState {
    object Idle : StreamState
    object Starting : StreamState
    object Streaming : StreamState
}

/**
 * Single entry point to the streaming core.
 *
 * Owns the MediaProjection foreground service connection and the active streamer,
 * exposes [state] and one-shot [errors]. Contains no UI or permission-request code:
 * callers must obtain RECORD_AUDIO and MediaProjection consent before calling [start].
 */
class StreamController(
    context: Context,
    private val configFactory: StreamConfigFactory = StreamConfigFactory(context.applicationContext)
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-shot error messages. The session is already back to [StreamState.Idle] when emitted. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val isStreaming: Boolean
        get() = _state.value is StreamState.Streaming

    private var connection: ServiceConnection? = null
    private var activeStreamer: IVideoStreamer<*>? = null
    private var streamingObserverJob: Job? = null

    /**
     * Starts the projection foreground service, binds to it, configures video/audio encoders,
     * and starts the stream.
     *
     * @param resultCode Result code from the MediaProjection consent dialog.
     * @param resultData Intent containing the MediaProjection token.
     */
    fun start(resultCode: Int, resultData: Intent) {
        if (_state.value != StreamState.Idle) {
            Log.w(TAG, "Stream session is already starting or active")
            return
        }

        // Check before starting the foreground service: on Android 14+ starting it with the
        // microphone type without the permission throws.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _errors.tryEmit("Permission RECORD_AUDIO must have been granted!")
            return
        }

        _state.value = StreamState.Starting

        val serviceIntent = Intent(context, DemoMediaProjectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        connection = MediaProjectionService.bindService(
            context = context,
            serviceClass = DemoMediaProjectionService::class.java,
            resultCode = resultCode,
            resultData = resultData,
            onServiceCreated = { streamer -> onStreamerReady(streamer as IVideoStreamer<*>) },
            onServiceDisconnected = {
                Log.i(TAG, "MediaProjection service disconnected")
                reset()
            },
            onExtra = { extras -> configFactory.putServiceExtras(extras) }
        )
    }

    /**
     * Gracefully stops the stream, unbinds from the service, and halts the foreground service.
     */
    fun stop() {
        val streamer = activeStreamer
        scope.launch {
            try {
                streamer?.stopStream()
            } catch (t: Throwable) {
                Log.e(TAG, "Error stopping streamer", t)
            }
            try {
                streamer?.close()
            } catch (t: Throwable) {
                Log.e(TAG, "Error closing streamer", t)
            }
            reset()
        }
    }

    /**
     * Switches the audio source of the running session. No-op when idle; the new value
     * will be picked up from [com.dimadesu.screenstreamergo.Configuration] on next [start].
     */
    fun changeAudioInput(preferenceValue: String) {
        if (_state.value == StreamState.Idle) return
        val intent = Intent(context, DemoMediaProjectionService::class.java)
            .setAction(Actions.CHANGE_AUDIO_INPUT.value)
            .putExtra(
                DemoMediaProjectionService.AUDIO_INPUT_KEY,
                StreamConfigFactory.audioInputExtra(preferenceValue)
            )
        context.startService(intent)
    }

    private fun onStreamerReady(streamer: IVideoStreamer<*>) {
        activeStreamer = streamer

        // Single source of truth for Streaming/Idle once the streamer exists. Also catches
        // stops initiated outside this class (e.g. the notification's Stop action).
        streamingObserverJob?.cancel()
        streamingObserverJob = scope.launch {
            streamer.isStreamingFlow.collect { isStreaming ->
                if (isStreaming) {
                    _state.value = StreamState.Streaming
                } else if (_state.value == StreamState.Streaming) {
                    reset()
                }
            }
        }

        scope.launch {
            try {
                configure(streamer)
                startStream(streamer)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to configure and start stream", t)
                reset()
                _errors.tryEmit(t.message ?: "Unknown error")
            }
        }
    }

    // RECORD_AUDIO is verified in start() before the service is launched.
    @SuppressLint("MissingPermission")
    private suspend fun configure(streamer: IVideoStreamer<*>) {
        val videoConfig = configFactory.videoConfig()
        when (streamer) {
            is IVideoSingleStreamer -> streamer.setVideoConfig(videoConfig)
            is IVideoDualStreamer -> streamer.setVideoConfig(DualStreamerVideoConfig(videoConfig))
            else -> throw IllegalStateException("Streamer is not a supported video streamer")
        }

        if (streamer !is IConfigurableAudioStreamer<*>) {
            Log.e(TAG, "Streamer does not support audio configuration")
            return
        }

        val audioConfig = configFactory.audioConfig()
        when (streamer) {
            is IAudioSingleStreamer -> streamer.setAudioConfig(audioConfig)
            is IAudioDualStreamer -> streamer.setAudioConfig(DualStreamerAudioConfig(audioConfig))
            else -> throw IllegalStateException("Streamer is not an audio streamer")
        }
    }

    private suspend fun startStream(streamer: IVideoStreamer<*>) {
        val descriptor = configFactory.mediaDescriptor()
        when (streamer) {
            is ISingleStreamer -> streamer.startStream(descriptor)
            is IDualStreamer -> {
                Log.w(TAG, "Only first output will be used")
                streamer.first.startStream(descriptor)
            }
            else -> throw IllegalStateException("Streamer is not a supported streamer")
        }
    }

    /**
     * Unbinds and stops the service and returns to [StreamState.Idle]. Idempotent.
     */
    private fun reset() {
        _state.value = StreamState.Idle
        activeStreamer = null

        connection?.let {
            try {
                context.unbindService(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unbind service", e)
            }
            connection = null
        }

        try {
            context.stopService(Intent(context, DemoMediaProjectionService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop service", e)
        }

        // Last: may cancel the coroutine that called reset().
        streamingObserverJob?.cancel()
        streamingObserverJob = null
    }

    companion object {
        private const val TAG = "StreamController"

        @Volatile
        private var instance: StreamController? = null

        fun getInstance(context: Context): StreamController {
            return instance ?: synchronized(this) {
                instance ?: StreamController(context.applicationContext).also { instance = it }
            }
        }
    }
}
