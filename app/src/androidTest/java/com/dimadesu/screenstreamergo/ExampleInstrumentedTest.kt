package com.dimadesu.screenstreamergo

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for ScreenStreamerGo executing on an Android device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO
        )
    } else {
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO
        )
    }

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.dimadesu.screenstreamergo", appContext.packageName)
    }

    @Test
    fun defaultConfiguration_hasValidParameters() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(appContext)

        // Validate video configuration defaults
        assertNotNull(config.video.encoder)
        assertTrue("Video width must be positive", config.video.resolution.width > 0)
        assertTrue("Video height must be positive", config.video.resolution.height > 0)
        assertTrue("Video bitrate must be positive", config.video.bitrate > 0)

        // Validate audio configuration defaults
        assertNotNull(config.audio.encoder)
        assertTrue("Audio channels must be >= 1", config.audio.numberOfChannels >= 1)
        assertTrue("Sample rate must be > 0", config.audio.sampleRate > 0)
        assertTrue("Audio bitrate must be > 0", config.audio.bitrate > 0)

        // Validate endpoint configuration defaults
        assertNotNull(config.endpoint.type)
        assertTrue("Port must be in valid range", config.endpoint.srt.port in 1..65535)
    }

    @Test
    fun streamCoordinator_buildsValidConfigsAndDescriptors() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = com.dimadesu.screenstreamergo.stream.StreamCoordinator(appContext)

        val fps = coordinator.getCalculatedFps()
        assertTrue("Calculated FPS must be > 0", fps > 0)

        val videoConfig = coordinator.createVideoConfig()
        assertNotNull(videoConfig)
        assertEquals(fps, videoConfig.fps)
        assertTrue("Bitrate must be positive", videoConfig.startBitrate > 0)

        val audioConfig = coordinator.createAudioConfig()
        assertNotNull(audioConfig)
        assertTrue("Sample rate must be positive", audioConfig.sampleRate > 0)

        val descriptor = coordinator.createMediaDescriptor()
        assertNotNull("MediaDescriptor must not be null", descriptor)

        val intent = android.content.Intent()
        coordinator.prepareServiceExtras(intent)
        assertTrue(
            "Extra must have audio input key",
            intent.hasExtra(com.dimadesu.screenstreamergo.services.DemoMediaProjectionService.AUDIO_INPUT_KEY)
        )
        assertTrue(
            "Extra must have CFR FPS key",
            intent.hasExtra(com.dimadesu.screenstreamergo.services.DemoMediaProjectionService.CFR_FPS_KEY)
        )
    }

    @Test
    fun streamManager_initializesInIdleState() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = com.dimadesu.screenstreamergo.stream.StreamManager.getInstance(appContext)

        assertTrue(
            "Initial state must be Idle",
            manager.state.value is com.dimadesu.screenstreamergo.stream.StreamState.Idle
        )
        assertFalse("Initially isStreaming should be false", manager.isStreaming)
    }

    @Test
    fun mainActivity_launchesAndDisplaysUiElements() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Verify main toggle button is displayed, visible, and initially not streaming
            onView(withId(R.id.liveButton))
                .check(matches(isDisplayed()))
                .check(matches(isNotChecked()))
                .check(matches(withText(R.string.record_screen)))

            // Verify the settings/actions FloatingActionButton is displayed
            onView(withId(R.id.actions))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun mainActivity_openActionsMenu() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Click the actions button to display the popup menu
            onView(withId(R.id.actions))
                .check(matches(isDisplayed()))
                .perform(click())

            // Verify settings action item is displayed in the popup menu
            onView(withText(R.string.action_settings))
                .check(matches(isDisplayed()))
        }
    }
}