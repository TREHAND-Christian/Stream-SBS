package com.treha.streamsbs55.common.video

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnexBTest {
    @Test
    fun splitAnnexBNalsSupportsThreeAndFourByteStartCodes() {
        val data = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x11,
            0, 0, 1, 0x68, 0x22,
            0, 0, 0, 1, 0x65, 0x33,
        )

        val nals = splitAnnexBNals(data)

        assertEquals(3, nals.size)
        assertContentEquals(byteArrayOf(0x67, 0x11), nals[0])
        assertContentEquals(byteArrayOf(0x68, 0x22), nals[1])
        assertContentEquals(byteArrayOf(0x65, 0x33), nals[2])
    }

    @Test
    fun nalTypeReturnsH264Type() {
        assertEquals(7, nalType(byteArrayOf(0x67)))
        assertEquals(8, nalType(byteArrayOf(0x68)))
        assertEquals(5, nalType(byteArrayOf(0x65)))
    }

    @Test
    fun splitAnnexBNalsReturnsEmptyListWithoutStartCode() {
        assertTrue(splitAnnexBNals(byteArrayOf(0x67, 0x11)).isEmpty())
    }
}
