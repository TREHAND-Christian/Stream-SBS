package com.treha.streamsbs55.common.video

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

val NAL_START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)

fun ByteBuffer.toByteArray(): ByteArray {
    val duplicate = duplicate()
    duplicate.clear()
    return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
}

fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
    val starts = mutableListOf<NalStart>()
    var index = 0
    while (index <= data.size - 4) {
        val length = when {
            index <= data.size - 4 &&
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() &&
                data[index + 3] == 1.toByte() -> 4

            index <= data.size - 3 &&
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte() -> 3

            else -> 0
        }
        if (length > 0) {
            starts += NalStart(startCodeIndex = index, payloadIndex = index + length)
            index += length
        } else {
            index += 1
        }
    }
    if (starts.isEmpty()) return emptyList()
    return starts.mapIndexed { idx, start ->
        val end = if (idx == starts.lastIndex) data.size else starts[idx + 1].startCodeIndex
        data.copyOfRange(start.payloadIndex, end.coerceAtLeast(start.payloadIndex))
    }.filter { it.isNotEmpty() }
}

private data class NalStart(
    val startCodeIndex: Int,
    val payloadIndex: Int,
)

fun firstNal(data: ByteArray?): ByteArray? = data?.let { splitAnnexBNals(it).firstOrNull() ?: it }

fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

fun parseSpsSize(spsNal: ByteArray): Pair<Int, Int>? {
    if (spsNal.isEmpty()) return null
    val rbsp = ByteArrayOutputStream(spsNal.size)
    rbsp.write(spsNal[0].toInt())
    var zeroCount = 0
    for (i in 1 until spsNal.size) {
        val value = spsNal[i].toInt() and 0xFF
        if (zeroCount == 2 && value == 0x03) {
            zeroCount = 0
            continue
        }
        rbsp.write(value)
        zeroCount = if (value == 0) zeroCount + 1 else 0
    }

    val bits = BitReader(rbsp.toByteArray())
    bits.readBits(8)
    val profileIdc = bits.readBits(8)
    bits.readBits(8)
    bits.readBits(8)
    bits.readUE()
    if (profileIdc in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138)) {
        val chromaFormatIdc = bits.readUE()
        if (chromaFormatIdc == 3) bits.readBit()
        bits.readUE()
        bits.readUE()
        bits.readBit()
        if (bits.readBit() == 1) {
            repeat(if (chromaFormatIdc != 3) 8 else 12) { i ->
                if (bits.readBit() == 1) skipScaling(bits, if (i < 6) 16 else 64)
            }
        }
    }
    bits.readUE()
    when (bits.readUE()) {
        0 -> bits.readUE()
        1 -> {
            bits.readBit()
            bits.readSE()
            bits.readSE()
            repeat(bits.readUE()) { bits.readSE() }
        }
    }
    bits.readUE()
    bits.readBit()
    val picWidthInMbsMinus1 = bits.readUE()
    val picHeightInMapUnitsMinus1 = bits.readUE()
    val frameMbsOnlyFlag = bits.readBit()
    if (frameMbsOnlyFlag == 0) bits.readBit()
    bits.readBit()
    val frameCropFlag = bits.readBit()
    var cropLeft = 0
    var cropRight = 0
    var cropTop = 0
    var cropBottom = 0
    if (frameCropFlag == 1) {
        cropLeft = bits.readUE()
        cropRight = bits.readUE()
        cropTop = bits.readUE()
        cropBottom = bits.readUE()
    }
    val width = ((picWidthInMbsMinus1 + 1) * 16) - ((cropLeft + cropRight) * 2)
    val height = ((2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16) -
        ((cropTop + cropBottom) * if (frameMbsOnlyFlag == 1) 2 else 4)
    return if (width > 0 && height > 0) width to height else null
}

private fun skipScaling(bits: BitReader, size: Int) {
    var lastScale = 8
    var nextScale = 8
    repeat(size) {
        if (nextScale != 0) {
            val delta = bits.readSE()
            nextScale = (lastScale + delta + 256) % 256
        }
        lastScale = if (nextScale == 0) lastScale else nextScale
    }
}

private class BitReader(private val data: ByteArray) {
    private var byteOffset = 0
    private var bitOffset = 0

    fun readBit(): Int {
        if (byteOffset >= data.size) return 0
        val bit = (data[byteOffset].toInt() shr (7 - bitOffset)) and 1
        bitOffset++
        if (bitOffset == 8) {
            bitOffset = 0
            byteOffset++
        }
        return bit
    }

    fun readBits(count: Int): Int {
        var out = 0
        repeat(count) { out = (out shl 1) or readBit() }
        return out
    }

    fun readUE(): Int {
        var leadingZeros = 0
        while (readBit() == 0 && leadingZeros < 32) leadingZeros++
        var value = (1 shl leadingZeros) - 1
        if (leadingZeros > 0) value += readBits(leadingZeros)
        return value
    }

    fun readSE(): Int {
        val codeNum = readUE()
        val signed = (codeNum + 1) / 2
        return if (codeNum % 2 == 0) -signed else signed
    }
}
