package com.treha.streamsbs.common.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceiverRenderConfigTest {
    @Test
    fun serializeAndParseRoundTrip() {
        val original = ReceiverRenderConfig(
            sbsEnabled = false,
            fullFrameEnabled = true,
            cameraEnabled = true,
            cameraOpacity = 0.42f,
            zoom = 1.25f,
            verticalZoom = 1.5f,
            horizontalOffset = -0.2f,
            verticalOffset = 0.35f,
            videoWidth = 1920,
            videoHeight = 1080,
            videoFrameRate = 24,
            videoBitRate = 8_000_000,
        )

        val parsed = ReceiverRenderConfig.parse(original.serialize())

        assertNotNull(parsed)
        assertFalse(parsed.sbsEnabled)
        assertTrue(parsed.fullFrameEnabled)
        assertTrue(parsed.cameraEnabled)
        assertEquals(0.42f, parsed.cameraOpacity, 0.001f)
        assertEquals(1.25f, parsed.zoom, 0.001f)
        assertEquals(1.5f, parsed.verticalZoom, 0.001f)
        assertEquals(-0.2f, parsed.horizontalOffset, 0.001f)
        assertEquals(0.35f, parsed.verticalOffset, 0.001f)
        assertEquals(1920, parsed.videoWidth)
        assertEquals(1080, parsed.videoHeight)
        assertEquals(24, parsed.videoFrameRate)
        assertEquals(8_000_000, parsed.videoBitRate)
    }

    @Test
    fun parseUsesSafeDefaultsForMissingValues() {
        val parsed = ReceiverRenderConfig.parse("camera=1")

        assertNotNull(parsed)
        assertTrue(parsed.sbsEnabled)
        assertFalse(parsed.fullFrameEnabled)
        assertTrue(parsed.cameraEnabled)
        assertEquals(1f, parsed.cameraOpacity)
        assertEquals(1f, parsed.zoom)
        assertEquals(1f, parsed.verticalZoom)
        assertEquals(0f, parsed.horizontalOffset)
        assertEquals(0f, parsed.verticalOffset)
        assertEquals(1280, parsed.videoWidth)
        assertEquals(720, parsed.videoHeight)
        assertEquals(30, parsed.videoFrameRate)
        assertEquals(10_000_000, parsed.videoBitRate)
    }

    @Test
    fun opacityIsClampedWhenParsingAndSerializing() {
        val high = ReceiverRenderConfig(cameraOpacity = 2f).serialize()
        val low = ReceiverRenderConfig.parse("opacity=-1")

        assertEquals(1f, ReceiverRenderConfig.parse(high)?.cameraOpacity)
        assertEquals(0f, low?.cameraOpacity)
    }
}
