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
import android.hardware.display.DisplayManager
import android.view.Display
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
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor

/**
 * Translates the user's [Configuration] into StreamPack configuration objects.
 *
 * Has no side effects and no knowledge of the service or streamer lifecycle,
 * which makes it the easiest part of the pipeline to test.
 */
class StreamConfigFactory(
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
     * Device screen refresh rate if the chosen video encoder supports it, otherwise 30.
     */
    fun fps(): Int {
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
    fun putServiceExtras(extras: Intent) {
        extras.putExtra(AUDIO_INPUT_KEY, audioInputExtra(configuration.audio.audioInput))
        extras.putExtra(CFR_FPS_KEY, fps())
    }

    /**
     * Creates the [VideoConfig] for the video encoder stage.
     */
    fun videoConfig(): VideoConfig {
        return VideoConfig(
            mimeType = configuration.video.encoder,
            startBitrate = configuration.video.bitrate * 1000, // to b/s
            resolution = configuration.video.resolution,
            fps = fps()
        )
    }

    /**
     * Creates the [AudioConfig] for the audio encoder stage.
     */
    fun audioConfig(): AudioConfig {
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
    fun mediaDescriptor(): MediaDescriptor {
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

    companion object {
        /**
         * Maps the audio input preference value to the extra value understood by the service.
         */
        fun audioInputExtra(preferenceValue: String?): String = when (preferenceValue) {
            "mediaProjection" -> AUDIO_INPUT_MEDIA_PROJECTION_KEY
            else -> AUDIO_INPUT_MICROPHONE_KEY
        }
    }
}
