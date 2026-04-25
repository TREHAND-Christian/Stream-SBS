package com.treha.streamsbs55.common.util

fun buildDeviceName(manufacturer: String?, model: String?, fallback: String): String {
    val cleanManufacturer = manufacturer?.trim().orEmpty()
    val cleanModel = model?.trim().orEmpty()
    return when {
        cleanManufacturer.isBlank() && cleanModel.isBlank() -> fallback
        cleanModel.startsWith(cleanManufacturer, ignoreCase = true) -> cleanModel
        cleanManufacturer.isBlank() -> cleanModel
        cleanModel.isBlank() -> cleanManufacturer
        else -> "$cleanManufacturer $cleanModel"
    }
}
