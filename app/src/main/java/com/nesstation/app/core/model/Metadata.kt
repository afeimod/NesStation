package com.nesstation.app.core.model

data class BoardInfo(
    val id: Int,
    val name: String,
    val battery: Boolean,
    val prgKbMax: Int,
    val chrKbMax: Int
)

data class RegionInfo(
    val id: Int,
    val code: String,
    val fps: Double,
    val cpuHz: Long,
    val ppuHz: Long,
    val sampleRateDefault: Int,
    val frameLines: Int
)
