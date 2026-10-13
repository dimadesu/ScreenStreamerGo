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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import com.dimadesu.screenstreamergo.Configuration
import com.dimadesu.screenstreamergo.models.EndpointType
import com.dimadesu.screenstreamergo.services.DemoMediaProjectionService.Companion.AUDIO_INPUT_KEY
import com.dimadesu.screenstreamergo.services.DemoMediaProjectionService.Companion.AUDIO_INPUT_MEDIA_PROJECTION_KEY
import com.dimadesu.screenstreamergo.services.DemoMediaProjectionService.Companion.AUDIO_INPUT_MICROPHONE_KEY
import com.dimadesu.screenstreamergo.services.DemoMediaProjectionService.Companion.CFR_FPS_KEY
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.encoders.mediacodec.MediaCodecHelper
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.data.TSServiceInfo
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.IConfigurableAudioStreamer
import io.github.thibaultbee.streampack.core.streamers.IVideoStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerAudioConfig
import io.github.thibaultbee.streampack.core.streamers.dual.DualStreamerVideoConfig
import io.github.thibaultbee.streampack.core.streamers.dual.IAudioDualStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.IDualStreamer
import io.github.thibaultbee.streampack.core.streamers.dual.IVideoDualStreamer
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.IAudioSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.IVideoSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor

/**
 * Coordinates the core streaming pipeline:
 * - Prepares Source parameters (audio source type, target FPS) for the projection service.
 * - Configures Video & Audio Encoders based on device capabilities and app configuration.
 * - Resolves Endpoint descriptors (SRT or RTMP) and initiates or stops stream transmission.
 */
class StreamCoordinator(
    private val context: Context,
    val configuration: Configuration = Configuration(context)
) {
    private val tsServiceInfo: TSServiceInfo
        get() = TSServiceInfo(
            TSServiceInfo.ServiceType.DIGITAL_TV,
            0x4698,
            configuration.muxer.service,
            configuration.muxer.provider
        )

    /**
     * Calculates the device screen refresh rate and checks compatibility with the chosen video encoder.
     */
    fun getCalculatedFps(): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val deviceRefreshRate = displayManager.getDisplay(Display.DEFAULT_DISPLAY).refreshRate.toInt()
        return if (MediaCodecHelper.Video.getFramerateRange(configuration.video.encoder)
                .contains(deviceRefreshRate)
        ) {
            deviceRefreshRate
        } else {
            30
        }
    }

    /**
     * Populates intent extras needed by [com.dimadesu.screenstreamergo.services.DemoMediaProjectionService]
     * to initialize video/audio sources.
     */
    fun prepareServiceExtras(extras: Intent) {
        val audioInputValue = when (configuration.audio.audioInput) {
            "mediaProjection" -> AUDIO_INPUT_MEDIA_PROJECTION_KEY
            else -> AUDIO_INPUT_MICROPHONE_KEY
        }
        extras.putExtra(AUDIO_INPUT_KEY, audioInputValue)
        extras.putExtra(CFR_FPS_KEY, getCalculatedFps())
    }

    /**
     * Creates the [VideoConfig] for the video encoder stage.
     */
    fun createVideoConfig(): VideoConfig {
        return VideoConfig(
            mimeType = configuration.video.encoder,
            startBitrate = configuration.video.bitrate * 1000, // to b/s
            resolution = configuration.video.resolution,
            fps = getCalculatedFps()
        )
    }

    /**
     * Creates the [AudioConfig] for the audio encoder stage.
     */
    fun createAudioConfig(): AudioConfig {
        return AudioConfig(
            mimeType = configuration.audio.encoder,
            startBitrate = configuration.audio.bitrate,
            sampleRate = configuration.audio.sampleRate,
            channelConfig = AudioConfig.getChannelConfig(configuration.audio.numberOfChannels),
            byteFormat = configuration.audio.byteFormat
        )
    }

    /**
     * Creates the destination [MediaDescriptor] for the sender stage (SRT or RTMP).
     */
    fun createMediaDescriptor(): MediaDescriptor {
        return when (configuration.endpoint.type) {
            EndpointType.SRT -> SrtMediaDescriptor(
                configuration.endpoint.srt.ip,
                configuration.endpoint.srt.port,
                configuration.endpoint.srt.streamID,
                configuration.endpoint.srt.passPhrase,
                serviceInfo = tsServiceInfo
            )
            EndpointType.RTMP -> UriMediaDescriptor(configuration.endpoint.rtmp.url.toUri())
        }
    }

    /**
     * Sequentially configures the video and audio encoders on the streamer.
     */
    suspend fun configure(streamer: IVideoStreamer<*>) {
        val videoConfig = createVideoConfig()
        when (streamer) {
            is IVideoSingleStreamer -> streamer.setVideoConfig(videoConfig)
            is IVideoDualStreamer -> streamer.setVideoConfig(DualStreamerVideoConfig(videoConfig))
            else -> throw IllegalStateException("Streamer is not a supported video streamer")
        }

        if (streamer !is IConfigurableAudioStreamer<*>) {
            Log.e(TAG, "Streamer does not support audio configuration")
            return
        }

        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Permission RECORD_AUDIO must have been granted!")
        }

        val audioConfig = createAudioConfig()
        when (streamer) {
            is IAudioSingleStreamer -> streamer.setAudioConfig(audioConfig)
            is IAudioDualStreamer -> streamer.setAudioConfig(DualStreamerAudioConfig(audioConfig))
            else -> throw IllegalStateException("Streamer is not an audio streamer")
        }
    }

    /**
     * Resolves the media descriptor and starts streaming.
     */
    suspend fun startStream(streamer: IVideoStreamer<*>) {
        val descriptor = createMediaDescriptor()
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
     * Stops the stream and releases the streamer resources.
     */
    suspend fun stopStream(streamer: IVideoStreamer<*>?) {
        try {
            streamer?.stopStream()
        } finally {
            streamer?.close()
        }
    }

    companion object {
        private const val TAG = "StreamCoordinator"
    }
}
