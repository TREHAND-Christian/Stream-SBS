package com.treha.streamsbs55.common.video

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LowLatencyFrameDropperTest {
    @Test
    fun rejectsOlderTimestampAfterNewerPacketWasAccepted() {
        val dropper = LowLatencyFrameDropper()

        assertTrue(dropper.shouldAcceptPacket(timestamp = 2000, sequence = 10))
        assertFalse(dropper.shouldAcceptPacket(timestamp = 1000, sequence = 11))
        assertFalse(dropper.shouldAcceptFrame(timestamp = 1000))
    }

    @Test
    fun rejectsDuplicateOrOlderSequenceForSameTimestamp() {
        val dropper = LowLatencyFrameDropper()

        assertTrue(dropper.shouldAcceptPacket(timestamp = 1000, sequence = 10))
        assertFalse(dropper.shouldAcceptPacket(timestamp = 1000, sequence = 10))
        assertFalse(dropper.shouldAcceptPacket(timestamp = 1000, sequence = 9))
        assertTrue(dropper.shouldAcceptPacket(timestamp = 1000, sequence = 11))
    }

    @Test
    fun resetAllowsOlderFramesAgain() {
        val dropper = LowLatencyFrameDropper()

        assertTrue(dropper.shouldAcceptPacket(timestamp = 2000, sequence = 10))
        assertFalse(dropper.shouldAcceptPacket(timestamp = 1000, sequence = 11))

        dropper.reset()

        assertTrue(dropper.shouldAcceptPacket(timestamp = 1000, sequence = 11))
    }
}
