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

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.util.Log
import com.dimadesu.screenstreamergo.services.DemoMediaProjectionService
import io.github.thibaultbee.streampack.core.streamers.IVideoStreamer
import io.github.thibaultbee.streampack.services.MediaProjectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Represents the observable lifecycle state of the streaming session.
 */
sealed interface StreamState {
    object Idle : StreamState
    object Starting : StreamState
    object Streaming : StreamState
    data class Error(val message: String) : StreamState
}

/**
 * Manages the MediaProjection foreground service connection, handles starting/stopping
 * the streaming pipeline via [StreamCoordinator], and exposes a reactive [state] flow.
 */
class StreamManager(
    private val context: Context,
    private val coordinator: StreamCoordinator = StreamCoordinator(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    val isStreaming: Boolean
        get() = _state.value is StreamState.Streaming

    private var connection: ServiceConnection? = null
    private var activeStreamer: IVideoStreamer<*>? = null
    private var isStreamingJob: Job? = null
    private var stopObserverJob: Job? = null

    /**
     * Starts the projection foreground service, binds to it, configures video/audio encoders,
     * and starts the stream.
     *
     * @param resultCode Result code from the MediaProjection consent dialog.
     * @param resultData Intent containing the MediaProjection token.
     * @param onStarted Optional callback invoked once the stream successfully starts.
     */
    fun startStreaming(
        resultCode: Int,
        resultData: Intent,
        onStarted: () -> Unit = {}
    ) {
        if (_state.value is StreamState.Starting || _state.value is StreamState.Streaming) {
            Log.w(TAG, "Stream session is already starting or active")
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
            onServiceCreated = { streamer ->
                val videoStreamer = streamer as IVideoStreamer<*>
                activeStreamer = videoStreamer

                scope.launch {
                    try {
                        coordinator.configure(videoStreamer)
                        coordinator.startStream(videoStreamer)
                        _state.value = StreamState.Streaming
                        onStarted()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to configure and start stream", t)
                        _state.value = StreamState.Error(t.message ?: "Unknown error")
                        cleanup()
                    }
                }

                isStreamingJob?.cancel()
                isStreamingJob = scope.launch {
                    videoStreamer.isStreamingFlow.collect { isStreaming ->
                        if (isStreaming) {
                            _state.value = StreamState.Streaming
                        }
                    }
                }

                stopObserverJob?.cancel()
                stopObserverJob = scope.launch {
                    videoStreamer.isStreamingFlow.drop(1).collect { isStreaming ->
                        if (!isStreaming) {
                            cleanup()
                            _state.value = StreamState.Idle
                        }
                    }
                }
            },
            onServiceDisconnected = {
                Log.i(TAG, "MediaProjection service disconnected")
                cleanup()
                _state.value = StreamState.Idle
            },
            onExtra = { extra ->
                coordinator.prepareServiceExtras(extra)
            }
        )
    }

    /**
     * Gracefully stops the stream, unbinds from the service, and halts the foreground service.
     */
    fun stopStreaming() {
        scope.launch {
            try {
                coordinator.stopStream(activeStreamer)
            } catch (t: Throwable) {
                Log.e(TAG, "Error stopping streamer", t)
            } finally {
                cleanup()
                _state.value = StreamState.Idle
            }
        }
    }

    private fun cleanup() {
        isStreamingJob?.cancel()
        isStreamingJob = null
        stopObserverJob?.cancel()
        stopObserverJob = null
        activeStreamer = null

        connection?.let {
            try {
                context.unbindService(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unbind service", e)
            }
            connection = null
        }

        val serviceIntent = Intent(context, DemoMediaProjectionService::class.java)
        try {
            context.stopService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop service", e)
        }
    }

    companion object {
        private const val TAG = "StreamManager"

        @Volatile
        private var instance: StreamManager? = null

        fun getInstance(context: Context): StreamManager {
            return instance ?: synchronized(this) {
                instance ?: StreamManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
