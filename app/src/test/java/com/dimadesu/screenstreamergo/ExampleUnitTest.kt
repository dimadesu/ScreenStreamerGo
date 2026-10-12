package com.dimadesu.screenstreamergo

import com.dimadesu.screenstreamergo.models.Endpoint
import com.dimadesu.screenstreamergo.models.EndpointFactory
import com.dimadesu.screenstreamergo.models.EndpointType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local unit tests for business logic and models running on the JVM.
 */
class ExampleUnitTest {

    @Test
    fun endpointType_fromId_returnsCorrectEnum() {
        assertEquals(EndpointType.SRT, EndpointType.fromId(0))
        assertEquals(EndpointType.RTMP, EndpointType.fromId(1))
    }

    @Test
    fun endpointFactory_buildsSrtEndpointWithExpectedCapabilities() {
        val endpoint = EndpointFactory(EndpointType.SRT).build()

        assertTrue(endpoint is Endpoint.SrtEndpoint)
        assertTrue(endpoint.hasSrtCapabilities)
        assertTrue(endpoint.hasTSCapabilities)
        assertFalse(endpoint.hasRtmpCapabilities)
        assertFalse(endpoint.hasFLVCapabilities)
    }

    @Test
    fun endpointFactory_buildsRtmpEndpointWithExpectedCapabilities() {
        val endpoint = EndpointFactory(EndpointType.RTMP).build()

        assertTrue(endpoint is Endpoint.RtmpEndpoint)
        assertTrue(endpoint.hasRtmpCapabilities)
        assertTrue(endpoint.hasFLVCapabilities)
        assertFalse(endpoint.hasSrtCapabilities)
        assertFalse(endpoint.hasTSCapabilities)
    }
}